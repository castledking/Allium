package codes.castled.allium.kit;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.economy.EconomyEngine;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.EconomySettings;
import codes.castled.allium.economy.Money;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.delivery.SkullFallback;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.pack.CoreItems;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class KitFoliaTest {

    @TempDir Path tempDir;

    private final ArrayDeque<Runnable> workers = new ArrayDeque<>();
    private final ArrayDeque<Runnable> actorTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> targetTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> targetRetires = new ArrayDeque<>();
    private final Map<NamespacedKey, Object> targetPdc = new HashMap<>();

    private PluginStart plugin;
    private Server server;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private KitRepository repository;
    private ItemService itemService;
    private ItemDeliveryService deliveryService;
    private Player actor;
    private Player target;
    private ServerThread actorOwner;
    private ServerThread targetOwner;
    private Path playerDataDir;
    private File dataFolder;
    private EconomyEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dataFolder = tempDir.resolve("plugin").toFile();
        assertTrue(dataFolder.mkdirs());
        playerDataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerDataDir);

        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        scheduler = mock(TaskScheduler.class);
        messages = mock(MessageManager.class);
        repository = mock(KitRepository.class);
        ModuleManager modules = mock(ModuleManager.class);
        EconomyModule economy = mock(EconomyModule.class);
        EconomyRuntime runtime = mock(EconomyRuntime.class);

        engine = new EconomyEngine(() -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("eco.db")),
                EconomySettings.defaults(), (c, f, t) -> true);

        itemService = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), msg -> {});
        CoreItems.definitions(CoreItems.loadManifest(getClass().getClassLoader()))
                .forEach(itemService::registerAlliumItem);

        when(plugin.getName()).thenReturn("Allium");
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getItemService()).thenReturn(itemService);
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));
        when(modules.getModule("economy")).thenReturn(economy);
        when(economy.isEnabled()).thenReturn(true);
        when(economy.runtime()).thenReturn(runtime);
        when(runtime.ready()).thenReturn(true);
        when(runtime.reservations()).thenReturn(engine.reservations());

        Permission wildcard = spy(new Permission("allium.kits.*", PermissionDefault.OP));
        doNothing().when(wildcard).recalculatePermissibles();
        Map<String, Permission> perms = new HashMap<>();
        perms.put("allium.kits.*", wildcard);
        when(pm.getPermission(anyString())).thenAnswer(c -> perms.get(c.getArgument(0)));
        doAnswer(c -> { Permission p = c.getArgument(0); perms.put(p.getName(), p); return null; })
                .when(pm).addPermission(any(Permission.class));

        actor = mock(Player.class);
        UUID actorId = UUID.randomUUID();
        when(actor.getUniqueId()).thenReturn(actorId);
        when(actor.getName()).thenReturn("Staff");
        when(actor.isOnline()).thenReturn(true);
        when(actor.hasPermission(anyString())).thenReturn(true);
        when(server.getPlayer(actorId)).thenReturn(actor);

        target = mock(Player.class);
        UUID targetId = UUID.randomUUID();
        PlayerInventory targetInv = mock(PlayerInventory.class);
        AtomicReference<ItemStack[]> storage = new AtomicReference<>(new ItemStack[36]);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Target");
        when(target.isOnline()).thenReturn(true);
        when(target.hasPermission(anyString())).thenReturn(true);
        when(target.getInventory()).thenReturn(targetInv);
        when(targetInv.getMaxStackSize()).thenReturn(64);
        when(targetInv.getStorageContents()).thenAnswer(c -> storage.get());
        doAnswer(c -> { storage.set(c.getArgument(0)); return null; }).when(targetInv).setStorageContents(any());
        var targetContainer = MockItemStacks.container(targetPdc);
        when(target.getPersistentDataContainer()).thenReturn(targetContainer);
        when(server.getPlayer(targetId)).thenReturn(target);

        var open = engine.request(UUID.randomUUID(), "test", "open", EconomyRequest.Kind.OPEN, "test", targetId,
                null, new Money(10_000));
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(open));

        actorOwner = mock(ServerThread.class);
        targetOwner = mock(ServerThread.class);
        when(actorOwner.isServerThread()).thenReturn(true);
        when(targetOwner.isServerThread()).thenReturn(false);

        when(scheduler.runOn(eq(actorOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            actorTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
        when(scheduler.runOn(eq(targetOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            targetTasks.add(c.getArgument(1));
            targetRetires.add(c.getArgument(2));
            return mock(TaskHandle.class);
        });
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);

        deliveryService = new ItemDeliveryService(ItemDeliveryService.targetThread(plugin, scheduler),
                server::getPlayer, pm, () -> SkullFallback.UNTEXTURED);
    }

    private KitService newService() throws Exception {
        KitDefinitionLoader loader = new KitDefinitionLoader(dataFolder, p -> null, m -> {}, m -> {});
        loader.save(new KitDefinition("starter", true, 0, false, "0.00", List.of("minecraft:diamond 1"), "kits/starter.yml"));
        ItemSpecParser parser = new ItemSpecParser(false);
        ItemLibrary library = mock(ItemLibrary.class);
        ItemStack diamond = MockItemStacks.create(org.bukkit.Material.DIAMOND);
        when(library.build(any(), eq(true)))
                .thenReturn(new ItemLibrary.Prepared(List.of(new ItemLibrary.PreparedItem(diamond, null)), List.of()));
        KitService service = new KitService(plugin, repository, loader, () -> parser, () -> library,
                () -> deliveryService, workers::add, "srv-1", playerDataDir);
        service.reloadDefinitions();
        return service;
    }

    @Test
    void crossRegionKitGrantRoutesDeliveryToTargetRegionAndFeedbackToActorRegion() throws Exception {
        KitService service = newService();
        when(repository.beginClaim(any(KitDeliveryRecord.class), anyLong()))
                .thenAnswer(c -> new KitRepository.BeginOutcome.Claiming(c.getArgument(0)));
        when(repository.paid(any())).thenReturn(false);
        when(repository.finishClaim(any(), any(), anyString(), anyLong())).thenReturn(true);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS);
             var proof = mockStatic(PlayerDataReceiptProof.class)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, actor)).thenReturn(actorOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, target)).thenReturn(targetOwner);
            proof.when(() -> PlayerDataReceiptProof.verify(any(), eq(PlayerDataReceiptProof.KIT_RECEIPTS), anyString()))
                    .thenAnswer(c -> null);

            // Called on actor's region thread (targetOwner.isServerThread() == false)
            service.claim(actor, target, "starter");
            assertEquals(1, targetTasks.size(), "startDurableClaim must hop onto target's region");
            assertTrue(workers.isEmpty());

            // Now execute on target's region thread
            when(actorOwner.isServerThread()).thenReturn(false);
            when(targetOwner.isServerThread()).thenReturn(true);
            targetTasks.remove().run(); // startDurableClaim
            workers.remove().run(); // beginClaim
            assertEquals(1, targetTasks.size());
            targetTasks.remove().run(); // deliverClaim (asserts targetThread.owns(target))
            workers.remove().run(); // finishClaim
            targetTasks.remove().run(); // finish on target's region

            verify(messages).send(eq(target), eq(Messages.KIT_RECEIVED), eq("{name}"), eq("starter"));
            verify(messages, never()).send(eq(actor), eq(Messages.KIT_GIVEN), any(String[].class));
            assertEquals(1, actorTasks.size(), "KIT_GIVEN must hop back to actor's region");

            when(actorOwner.isServerThread()).thenReturn(true);
            when(targetOwner.isServerThread()).thenReturn(false);
            actorTasks.remove().run();
            verify(messages).send(eq(actor), eq(Messages.KIT_GIVEN), eq("{name}"), eq("starter"), eq("{player}"), eq("Target"));
            verify(scheduler, never()).runOnServer(any());
        }
    }

    @Test
    void paidKitSignOnFoliaReleasesHoldWhenSignBecomesInvalidBeforeDelivery() throws Exception {
        KitService service = newService();
        when(repository.beginClaim(any(), any(KitDeliveryRecord.class), anyLong()))
                .thenAnswer(c -> new KitRepository.BeginOutcome.Claiming(c.getArgument(1)));
        when(repository.findDelivery(any())).thenAnswer(c -> Optional.of(new KitDeliveryRecord(
                c.getArgument(0), target.getUniqueId(), "starter", UUID.randomUUID(), "srv-1",
                "diamond 1", 1, 0L, false, false, System.currentTimeMillis())));
        when(repository.paid(any())).thenReturn(true);
        when(repository.releaseClaim(any(), any(), any(), anyString())).thenReturn(true);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS);
             var proof = mockStatic(PlayerDataReceiptProof.class)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, target)).thenReturn(targetOwner);
            when(targetOwner.isServerThread()).thenReturn(true);
            proof.when(() -> PlayerDataReceiptProof.verifyAbsent(any(), eq(PlayerDataReceiptProof.KIT_RECEIPTS)))
                    .thenAnswer(c -> null);

            AtomicBoolean valid = new AtomicBoolean(true);
            service.claimFromSign(target, "starter", new Money(350), valid::get);
            workers.remove().run(); // beginClaim + reserve
            var held = assertInstanceOf(EconomyResult.Found.class, engine.balance(target.getUniqueId()));
            assertEquals(350L, held.reserved().minor());

            valid.set(false);
            targetTasks.remove().run(); // deliverClaim sees !valid -> queues release
            workers.remove().run(); // releaseClaim + release reservation
            targetTasks.remove().run(); // finishInFlight

            var released = assertInstanceOf(EconomyResult.Found.class, engine.balance(target.getUniqueId()));
            assertEquals(10_000L, released.book().minor());
            assertEquals(0L, released.reserved().minor());
            assertFalse(service.busy(target));
            verify(scheduler, never()).runOnServer(any());
        }
    }

    @Test
    void foliaRetirementBeforeDeliveryReleasesClaimWithoutTouchingRetiredPlayer() throws Exception {
        KitService service = newService();
        when(repository.beginClaim(any(KitDeliveryRecord.class), anyLong()))
                .thenAnswer(c -> new KitRepository.BeginOutcome.Claiming(c.getArgument(0)));
        when(repository.paid(any())).thenReturn(false);
        when(repository.releaseClaim(any(), any(), anyString())).thenReturn(true);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, target)).thenReturn(targetOwner);
            when(targetOwner.isServerThread()).thenReturn(true);

            service.claim(target, target, "starter");
            workers.remove().run(); // beginClaim
            targetTasks.clear();
            targetRetires.removeLast().run(); // target retired before deliverClaim

            assertEquals(1, workers.size(), "retirement should queue releaseClaim");
            workers.remove().run();
            targetRetires.removeLast().run(); // retired completion cleans up lock/inFlight
            assertFalse(service.busy(target));
            verify(repository).releaseClaim(any(), any(), eq("srv-1"));
            verify(scheduler, never()).runOnServer(any());
        }
    }
}
