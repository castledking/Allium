package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.economy.EconomyEngine;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.EconomySettings;
import codes.castled.allium.economy.Money;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaidSignServiceTest {
    @TempDir Path data;
    private final UUID account = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final Server server = mock(Server.class);
    private final MessageManager messages = mock(MessageManager.class);
    private final ArrayDeque<Runnable> worker = new ArrayDeque<>();
    private final ArrayDeque<Runnable> main = new ArrayDeque<>();
    private final SignIdentity sign = new SignIdentity(UUID.randomUUID(), SignIdentity.Type.WARP,
            "market", true, new Money(300));
    private PaidSignService signs;
    private EconomyEngine engine;
    private PluginStart plugin;
    private TaskScheduler scheduler;

    @BeforeEach void setUp() throws Exception {
        engine = new EconomyEngine(() -> DriverManager.getConnection("jdbc:sqlite:" + data.resolve("economy.db")),
                EconomySettings.defaults(), (connection, from, to) -> true);
        var open = engine.request(UUID.randomUUID(), "test", "open", EconomyRequest.Kind.OPEN, "test", account,
                null, new Money(10000));
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(open));

        plugin = mock(PluginStart.class);
        scheduler = mock(TaskScheduler.class);
        var pool = mock(ThreadPoolExecutor.class);
        var modules = mock(ModuleManager.class);
        var economy = mock(EconomyModule.class);
        var runtime = mock(EconomyRuntime.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(modules.getModule("economy")).thenReturn(economy);
        when(economy.isEnabled()).thenReturn(true);
        when(economy.runtime()).thenReturn(runtime);
        when(runtime.ready()).thenReturn(true);
        when(runtime.engine()).thenReturn(engine);
        when(runtime.reservations()).thenReturn(engine.reservations());
        when(runtime.synchronousBalance(any())).thenAnswer(call -> engine.balance(call.getArgument(0)));
        when(runtime.synchronous(any())).thenAnswer(call -> engine.execute(call.getArgument(0)));
        when(plugin.logger()).thenReturn(mock(codes.castled.allium.util.AlliumLogger.class));
        when(player.getUniqueId()).thenReturn(account);
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayer(account)).thenReturn(player);
        when(scheduler.boundedPool("Allium-PaidSign", 1, 128)).thenReturn(pool);
        doAnswer(call -> { worker.add(call.getArgument(0)); return null; }).when(pool).execute(any());
        when(scheduler.runOnServer(any())).thenAnswer(call -> {
            main.add(call.getArgument(0));
            return TaskHandle.NONE;
        });
        signs = new PaidSignService(plugin);
    }

    @Test void debitsOnceAfterDeferredDelivery() {
        var report = new AtomicReference<Consumer<Boolean>>();
        signs.start(player, sign, () -> true, (valid, complete) -> report.set(complete));
        worker.remove().run();
        main.remove().run();

        assertTrue(signs.busy(player));
        assertBalance(10000, 300);
        report.get().accept(true);
        report.get().accept(false);
        settle();
        assertBalance(9700, 0);
        assertTrue(worker.isEmpty());
        verify(messages).send(eq(player), eq(Messages.SIGN_PAID), eq("{price}"), anyString(), eq("{type}"), eq("Warp"));
    }

    @Test void refusesToDeliverToAReconnectedSessionAndReleasesTheHold() {
        signs.start(player, sign, () -> true, (valid, complete) -> fail("delivered to stale session"));
        worker.remove().run();
        when(server.getPlayer(account)).thenReturn(mock(Player.class));
        main.remove().run();
        settle();
        assertBalance(10000, 0);
        verifyNoInteractions(messages);
    }

    @Test void rechecksPermissionsWhenTheDeferredActionIsReady() {
        var canUse = new AtomicBoolean(true);
        var finish = new AtomicReference<Runnable>();
        signs.start(player, sign, canUse::get,
                (valid, complete) -> finish.set(() -> complete.accept(valid.getAsBoolean())));
        worker.remove().run();
        main.remove().run();
        canUse.set(false);
        finish.get().run();
        settle();
        assertBalance(10000, 0);
        verifyNoInteractions(messages);
    }

    @Test void foliaRetirementBeforeDeliveryReleasesTheReservation() {
        ServerThread region = mock(ServerThread.class);
        var retire = new ArrayDeque<Runnable>();
        when(scheduler.runOn(eq(region), any(Runnable.class), any(Runnable.class), eq(0L))).thenAnswer(call -> {
            retire.add(call.getArgument(2));
            return mock(TaskHandle.class);
        });

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(region);
            signs.start(player, sign, () -> true, (valid, complete) -> fail("delivered after the player retired"));
            worker.remove().run();
            assertBalance(10000, 300);

            Runnable ownerRetired = retire.remove();
            ownerRetired.run();
            ownerRetired.run();
            assertEquals(1, worker.size(), "the reservation is released once");
            worker.remove().run();
            retire.remove().run();

            assertBalance(10000, 0);
            assertFalse(signs.busy(player));
            assertTrue(main.isEmpty(), "Folia player work bypasses the global scheduler");
            verifyNoInteractions(messages);
        }
    }

    @Test void buySignReservesDeliversCapturesAndReleasesWhenInventoryFills() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack[] storage = new ItemStack[]{null, null};
        when(inventory.getStorageContents()).thenAnswer(call -> storage);
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new java.util.HashMap<>());

        SignIdentity buySign = new SignIdentity(UUID.randomUUID(), SignIdentity.Type.BUY, "4|stone", false, new Money(500), true);
        ItemStack template = new ItemStack(Material.STONE, 1);

        // 1. Happy path: reserves -> delivers 4 stone -> captures $5.
        signs.startBuy(player, buySign, template, 4, "Stone", () -> true);
        worker.remove().run(); // reserve
        assertBalance(10000, 500);
        main.remove().run(); // deliver
        settle(); // capture + notify
        assertBalance(9500, 0);
        verify(inventory).addItem(argThat((ItemStack added) ->
                added.getType() == Material.STONE && added.getAmount() == 4));
        verify(messages).send(eq(player), eq(Messages.SIGN_BUY_BOUGHT),
                eq("{amount}"), eq("4"), eq("{item}"), eq("Stone"), eq("{price}"), anyString());

        // 2. Inventory fills up between reserve and delivery -> hold released, no charge.
        storage[0] = new ItemStack(Material.DIRT, 64);
        storage[1] = new ItemStack(Material.DIRT, 64);
        signs.startBuy(player, buySign, template, 4, "Stone", () -> true);
        worker.remove().run(); // reserve
        assertBalance(9500, 500);
        main.remove().run(); // sees full inventory -> refuses delivery
        settle(); // releases hold
        assertBalance(9500, 0);
        verify(messages).send(eq(player), eq(Messages.SIGN_BUY_INVENTORY_FULL),
                eq("{amount}"), eq("4"), eq("{item}"), eq("Stone"));
    }

    @Test void sellSignRemovesExactItemsDepositsAndRestoresOnRejectedDeposit() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack[] storage = new ItemStack[]{new ItemStack(Material.STONE, 10), null};
        when(inventory.getStorageContents()).thenAnswer(call -> storage);
        doAnswer(call -> {
            storage[(int) call.getArgument(0)] = call.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new java.util.HashMap<>());

        SignIdentity sellSign = new SignIdentity(UUID.randomUUID(), SignIdentity.Type.SELL, "4|stone", false, new Money(500), true);
        ItemStack template = new ItemStack(Material.STONE, 1);

        // 1. Happy path: pre-check -> remove 4 of 10 stone -> deposit $5 -> notify.
        signs.startSell(player, sellSign, template, 4, "Stone", () -> true);
        worker.remove().run(); // pre-check balance/cap
        main.remove().run(); // revalidate & remove 4 stone
        assertEquals(6, storage[0].getAmount());
        worker.remove().run(); // deposit $5
        main.remove().run(); // notify & clear busy
        assertFalse(signs.busy(player));
        assertBalance(10500, 0);
        verify(messages).send(eq(player), eq(Messages.SIGN_SELL_SOLD),
                eq("{amount}"), eq("4"), eq("{item}"), eq("Stone"), eq("{price}"), anyString());

        // 2. Items vanish before player-thread step -> refuses without depositing.
        storage[0] = new ItemStack(Material.STONE, 2);
        signs.startSell(player, sellSign, template, 4, "Stone", () -> true);
        worker.remove().run(); // pre-check
        main.remove().run(); // only 2 available < 4
        assertFalse(signs.busy(player));
        assertTrue(worker.isEmpty());
        assertBalance(10500, 0);
        verify(messages).send(eq(player), eq(Messages.SIGN_SELL_NOT_ENOUGH),
                eq("{amount}"), eq("4"), eq("{item}"), eq("Stone"), eq("{available}"), eq("2"));

        // 3. Deposit rejected after item removal -> restores exact removed items to inventory.
        storage[0] = new ItemStack(Material.STONE, 4);
        var runtime = ((EconomyModule) plugin.getModuleManager().getModule("economy")).runtime();
        doReturn(new EconomyResult.Rejected(UUID.randomUUID(), "CAP")).when(runtime).synchronous(any());
        signs.startSell(player, sellSign, template, 4, "Stone", () -> true);
        worker.remove().run(); // pre-check passes
        main.remove().run(); // removes 4 stone
        assertNull(storage[0]);
        worker.remove().run(); // deposit returns Rejected("CAP")
        main.remove().run(); // restores 4 stone & notifies refusal
        assertFalse(signs.busy(player));
        assertBalance(10500, 0);
        verify(inventory).addItem(argThat((ItemStack restored) ->
                restored.getType() == Material.STONE && restored.getAmount() == 4));
    }

    private void settle() {
        worker.remove().run();
        main.remove().run();
        assertFalse(signs.busy(player));
    }

    private void assertBalance(long book, long reserved) {
        var balance = assertInstanceOf(EconomyResult.Found.class, engine.balance(account));
        assertEquals(book, balance.book().minor());
        assertEquals(reserved, balance.reserved().minor());
    }
}
