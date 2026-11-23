package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandRenderer;
import codes.castled.allium.command.CommandManager;
import codes.castled.allium.command.essentials.TimeCommand;
import codes.castled.allium.command.essentials.WeatherCommand;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.economy.EconomyEngine;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.EconomySettings;
import codes.castled.allium.economy.Money;
import codes.castled.allium.mail.PlayerDataFiles;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.module.EssentialsModule;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.module.RtpModule;
import codes.castled.allium.rtp.RtpService;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rotatable;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaidSignFoliaTest {

    @TempDir Path data;

    private final UUID account = UUID.randomUUID();
    private final ArrayDeque<Runnable> signWorkers = new ArrayDeque<>();
    private final ArrayDeque<Runnable> repairWorkers = new ArrayDeque<>();
    private final ArrayDeque<Runnable> globalTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> playerTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> playerRetires = new ArrayDeque<>();
    private final Map<NamespacedKey, String> playerPdcMap = new HashMap<>();
    private final Map<NamespacedKey, String> signPdcMap = new HashMap<>();

    private EconomyEngine engine;
    private PluginStart plugin;
    private Server server;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private Player player;
    private World world;
    private ServerThread playerOwner;
    private ServerThread signRegion;
    private CommandManager commands;
    private CommandFamilyPolicy policy;
    private RtpService rtpService;
    private Block block;
    private Sign signBlock;
    private PaidSignService paidSigns;

    @BeforeEach
    void setUp() throws Exception {
        engine = new EconomyEngine(() -> DriverManager.getConnection("jdbc:sqlite:" + data.resolve("economy.db")),
                EconomySettings.defaults(), (connection, from, to) -> true);
        var open = engine.request(UUID.randomUUID(), "test", "open", EconomyRequest.Kind.OPEN, "test", account,
                null, new Money(10_000));
        assertInstanceOf(EconomyResult.Committed.class, engine.execute(open));

        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        scheduler = mock(TaskScheduler.class);
        messages = mock(MessageManager.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        ModuleManager modules = mock(ModuleManager.class);
        EconomyModule economy = mock(EconomyModule.class);
        EconomyRuntime runtime = mock(EconomyRuntime.class);
        EssentialsModule essentials = mock(EssentialsModule.class);
        KitsModule kits = mock(KitsModule.class);
        RtpModule rtp = mock(RtpModule.class);
        rtpService = mock(RtpService.class);
        commands = mock(CommandManager.class);
        policy = mock(CommandFamilyPolicy.class);
        ThreadPoolExecutor signPool = mock(ThreadPoolExecutor.class);
        ThreadPoolExecutor repairPool = mock(ThreadPoolExecutor.class);

        YamlConfiguration config = new YamlConfiguration();
        config.set("enabledSigns", Arrays.stream(SignIdentity.Type.values()).map(SignIdentity.Type::key).toList());
        config.set("sign-use-per-second", 10);

        when(plugin.getName()).thenReturn("Allium");
        when(plugin.logger()).thenReturn(logger);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getCommandManager()).thenReturn(commands);
        when(commands.getFamilyPolicy()).thenReturn(policy);
        when(policy.isDisabled(anyString())).thenReturn(false);
        when(policy.admission(any(), anyString(), any())).thenReturn(CommandFamilyPolicy.Admission.ALLOWED);

        when(modules.getModule("economy")).thenReturn(economy);
        when(economy.isEnabled()).thenReturn(true);
        when(economy.runtime()).thenReturn(runtime);
        when(runtime.ready()).thenReturn(true);
        when(runtime.reservations()).thenReturn(engine.reservations());

        when(modules.getModule("essentials")).thenReturn(essentials);
        when(essentials.isEnabled()).thenReturn(true);
        when(modules.getModule("kits")).thenReturn(kits);
        when(kits.isEnabled()).thenReturn(true);
        when(modules.getModule("rtp")).thenReturn(rtp);
        when(rtp.isEnabled()).thenReturn(true);
        when(rtp.rtp()).thenReturn(rtpService);

        when(scheduler.boundedPool("Allium-PaidSign", 1, 128)).thenReturn(signPool);
        when(scheduler.boundedPool("Allium-PaidRepair", 1, 128)).thenReturn(repairPool);
        doAnswer(call -> { signWorkers.add(call.getArgument(0)); return null; }).when(signPool).execute(any());
        doAnswer(call -> { repairWorkers.add(call.getArgument(0)); return null; }).when(repairPool).execute(any());

        when(scheduler.isServerThread()).thenReturn(false);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(call -> {
            globalTasks.add(call.getArgument(0));
            return mock(TaskHandle.class);
        });

        playerOwner = mock(ServerThread.class);
        signRegion = mock(ServerThread.class);
        when(playerOwner.isServerThread()).thenReturn(true);
        when(signRegion.isServerThread()).thenReturn(true);
        when(scheduler.runOn(eq(playerOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(call -> {
            playerTasks.add(call.getArgument(1));
            playerRetires.add(call.getArgument(2));
            return mock(TaskHandle.class);
        });

        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);

        player = mock(Player.class);
        PersistentDataContainer playerPdc = mock(PersistentDataContainer.class);
        when(player.getUniqueId()).thenReturn(account);
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.5, 65, 2.5));
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 64, 2.5));
        when(player.getPersistentDataContainer()).thenReturn(playerPdc);
        when(playerPdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> playerPdcMap.get(call.getArgument(0)));
        doAnswer(call -> { playerPdcMap.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(playerPdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        doAnswer(call -> { playerPdcMap.remove(call.getArgument(0)); return null; })
                .when(playerPdc).remove(any(NamespacedKey.class));
        when(server.getPlayer(account)).thenReturn(player);

        block = mock(Block.class);
        signBlock = mock(Sign.class);
        PersistentDataContainer signPdc = mock(PersistentDataContainer.class);
        Rotatable rot = mock(Rotatable.class);
        when(rot.getRotation()).thenReturn(BlockFace.SOUTH);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(0);
        when(block.getZ()).thenReturn(0);
        when(block.getState()).thenReturn(signBlock);
        when(signBlock.getBlockData()).thenReturn(rot);
        when(signBlock.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(signBlock.getPersistentDataContainer()).thenReturn(signPdc);
        when(signPdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> signPdcMap.get(call.getArgument(0)));

        paidSigns = new PaidSignService(plugin);
    }

    @Test
    void paidTimeSignOnFoliaWaitsForGlobalTickConfirmationBeforeCapture() {
        AtomicLong currentTime = new AtomicLong(6000L);
        doAnswer(call -> { currentTime.set(call.getArgument(0)); return null; }).when(world).setTime(anyLong());
        when(world.getTime()).thenAnswer(call -> currentTime.get());

        TimeCommand timeCommand = new TimeCommand(plugin, CoreCommandSpecs.TIME, null);
        BukkitCommandRenderer renderer = mock(BukkitCommandRenderer.class);
        when(renderer.handler()).thenReturn(timeCommand);
        when(commands.getRenderedCommands()).thenReturn(Map.of("time", renderer));
        PluginCommand pluginCmd = mock(PluginCommand.class);
        when(plugin.getCommand("time")).thenReturn(pluginCmd);

        SignIdentity identity = new SignIdentity(UUID.randomUUID(), SignIdentity.Type.TIME, "day", false, new Money(400));
        signPdcMap.put(new NamespacedKey("allium", "sign_front"), identity.encode());
        SignService service = new SignService(plugin, null, paidSigns);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(playerOwner);
            platform.when(() -> BukkitServerThread.region(eq(plugin), eq(world), anyInt(), anyInt())).thenReturn(signRegion);

            service.using(clickSign());
            // 1. Run delayed click activation on player's entity region
            playerTasks.remove().run();
            // 2. Run reservation worker
            signWorkers.remove().run();
            assertBalance(10_000, 400);
            // 3. Run PaidSignService.deliver on player's entity region -> queues global world.setTime
            playerTasks.remove().run();

            assertEquals(1, globalTasks.size(), "time change must be scheduled on global tick thread");
            assertTrue(signWorkers.isEmpty(), "payment must NOT be settled before global tick applies time");
            assertTrue(paidSigns.busy(player));
            assertBalance(10_000, 400);

            // 4. Global tick applies time change and confirms
            globalTasks.remove().run();
            assertEquals(TimeCommand.DAY_TICKS, currentTime.get());
            assertEquals(1, signWorkers.size(), "settlement should run after global confirmation");

            // 5. Settlement captures funds and sends confirmation on player's entity region
            signWorkers.remove().run();
            assertEquals(1, playerTasks.size());
            playerTasks.remove().run();

            assertBalance(9_600, 0);
            assertFalse(paidSigns.busy(player));
            verify(messages).send(eq(player), eq(Messages.SIGN_PAID), eq("{price}"), anyString(), eq("{type}"), eq("Time"));
        }
    }

    @Test
    void paidTimeSignOnFoliaReleasesHoldWhenGlobalTimeChangeDoesNotApply() {
        // World refuses time change (getTime stays 6000L even after setTime(1000L))
        when(world.getTime()).thenReturn(6000L);

        TimeCommand timeCommand = new TimeCommand(plugin, CoreCommandSpecs.TIME, null);
        BukkitCommandRenderer renderer = mock(BukkitCommandRenderer.class);
        when(renderer.handler()).thenReturn(timeCommand);
        when(commands.getRenderedCommands()).thenReturn(Map.of("time", renderer));
        PluginCommand pluginCmd = mock(PluginCommand.class);
        when(plugin.getCommand("time")).thenReturn(pluginCmd);

        SignIdentity identity = new SignIdentity(UUID.randomUUID(), SignIdentity.Type.TIME, "day", false, new Money(400));
        signPdcMap.put(new NamespacedKey("allium", "sign_front"), identity.encode());
        SignService service = new SignService(plugin, null, paidSigns);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(playerOwner);
            platform.when(() -> BukkitServerThread.region(eq(plugin), eq(world), anyInt(), anyInt())).thenReturn(signRegion);

            service.using(clickSign());
            playerTasks.remove().run();
            signWorkers.remove().run();
            playerTasks.remove().run();
            globalTasks.remove().run();
            signWorkers.remove().run();
            playerTasks.remove().run();

            assertBalance(10_000, 0);
            assertFalse(paidSigns.busy(player));
            verify(messages, never()).send(eq(player), eq(Messages.SIGN_PAID), any(String[].class));
        }
    }

    @Test
    void paidWeatherSignOnFoliaWaitsForGlobalTickAndReleasesOnFailure() {
        AtomicBoolean storm = new AtomicBoolean(false);
        AtomicBoolean thunder = new AtomicBoolean(false);
        // Simulate weather change succeeding on first sign, failing on second
        AtomicBoolean allowChange = new AtomicBoolean(true);
        doAnswer(call -> { if (allowChange.get()) storm.set(call.getArgument(0)); return null; }).when(world).setStorm(anyBoolean());
        doAnswer(call -> { if (allowChange.get()) thunder.set(call.getArgument(0)); return null; }).when(world).setThundering(anyBoolean());
        when(world.hasStorm()).thenAnswer(call -> storm.get());
        when(world.isThundering()).thenAnswer(call -> thunder.get());

        WeatherCommand weatherCommand = new WeatherCommand(plugin);
        BukkitCommandRenderer renderer = mock(BukkitCommandRenderer.class);
        when(renderer.handler()).thenReturn(weatherCommand);
        when(commands.getRenderedCommands()).thenReturn(Map.of("weather", renderer));
        PluginCommand pluginCmd = mock(PluginCommand.class);
        when(plugin.getCommand("weather")).thenReturn(pluginCmd);

        SignIdentity identity = new SignIdentity(UUID.randomUUID(), SignIdentity.Type.WEATHER, "storm", false, new Money(250));
        signPdcMap.put(new NamespacedKey("allium", "sign_front"), identity.encode());
        SignService service = new SignService(plugin, null, paidSigns);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(playerOwner);
            platform.when(() -> BukkitServerThread.region(eq(plugin), eq(world), anyInt(), anyInt())).thenReturn(signRegion);

            // Success path
            service.using(clickSign());
            playerTasks.remove().run();
            signWorkers.remove().run();
            playerTasks.remove().run();
            assertEquals(1, globalTasks.size());
            assertTrue(signWorkers.isEmpty());
            assertBalance(10_000, 250);

            globalTasks.remove().run();
            signWorkers.remove().run();
            playerTasks.remove().run();
            assertBalance(9_750, 0);
            assertTrue(storm.get());

            // Failure path (weather change rejected)
            allowChange.set(false);
            storm.set(false);
            new SignService(plugin, null, paidSigns).using(clickSign());
            playerTasks.remove().run();
            signWorkers.remove().run();
            playerTasks.remove().run();
            globalTasks.remove().run();
            signWorkers.remove().run();
            playerTasks.remove().run();

            assertBalance(9_750, 0);
            assertFalse(paidSigns.busy(player));
        }
    }

    @Test
    void paidRepairOnFoliaRoutesEveryPlayerStepToEntityRegionAndHonorsValidityRecheck() {
        AtomicReference<String> savedReceipt = new AtomicReference<>(null);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack damaged = mockDamagedSword();
        when(player.getInventory()).thenReturn(inventory);
        when(player.getItemOnCursor()).thenReturn(null);
        when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventory.getContents()).thenReturn(new ItemStack[]{damaged});
        when(inventory.getItem(0)).thenReturn(damaged);
        doAnswer(call -> {
            savedReceipt.set(playerPdcMap.get(new NamespacedKey("allium", "repair_receipts")));
            return null;
        }).when(player).saveData();

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS);
             var files = mockStatic(PlayerDataFiles.class);
             var proof = mockStatic(PlayerDataReceiptProof.class)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(playerOwner);
            files.when(() -> PlayerDataFiles.directory(server)).thenReturn(data);
            proof.when(() -> PlayerDataReceiptProof.verifyAbsent(any(), eq(PlayerDataReceiptProof.REPAIR_RECEIPTS)))
                    .thenAnswer(call -> {
                        assertNull(savedReceipt.get());
                        return null;
                    });
            proof.when(() -> PlayerDataReceiptProof.verify(any(), eq(PlayerDataReceiptProof.REPAIR_RECEIPTS), anyString()))
                    .thenAnswer(call -> {
                        assertEquals(call.getArgument(2), savedReceipt.get());
                        return null;
                    });

            PaidRepairService repairs = new PaidRepairService(plugin);

            // 1. Happy path
            repairs.start(player, "hand", new Money(500), () -> true);
            assertTrue(repairs.busy(player));
            repairWorkers.remove().run(); // prove baseline + reserve
            assertBalance(10_000, 500);
            assertEquals(1, playerTasks.size());

            playerTasks.remove().run(); // apply + saveData
            assertNotNull(savedReceipt.get());
            repairWorkers.remove().run(); // prove receipt + capture
            playerTasks.remove().run(); // clear receipt + notify

            assertBalance(9_500, 0);
            assertFalse(repairs.busy(player));
            assertNull(playerPdcMap.get(new NamespacedKey("allium", "repair_receipts")));
            verify(messages).send(eq(player), eq(Messages.SIGN_REPAIR_PAID), eq("{items}"), anyString(), eq("{price}"), anyString());
            assertTrue(globalTasks.isEmpty(), "PaidRepairService on Folia must never touch global scheduler");

            // 2. Sign becomes invalid before apply -> hold is released and inventory untouched
            savedReceipt.set(null);
            AtomicBoolean valid = new AtomicBoolean(true);
            repairs.start(player, "hand", new Money(500), valid::get);
            repairWorkers.remove().run();
            assertBalance(9_500, 500);
            valid.set(false);

            playerTasks.remove().run(); // sees !valid -> queues releaseNow
            repairWorkers.remove().run(); // proves absent + releases hold
            playerTasks.remove().run(); // clears busy + notifies changed

            assertBalance(9_500, 0);
            assertFalse(repairs.busy(player));
            verify(messages).send(eq(player), eq(Messages.SIGN_REPAIR_CHANGED), eq("{price}"), anyString());
            assertTrue(globalTasks.isEmpty());

            // 3. Entity retires before apply -> busy is cleared without touching global scheduler
            repairs.start(player, "hand", new Money(500), () -> true);
            repairWorkers.remove().run();
            playerTasks.clear();
            playerRetires.removeLast().run();
            assertFalse(repairs.busy(player));
            assertTrue(globalTasks.isEmpty());
        }
    }

    @Test
    void paidRepairRecoveryOnFoliaSettlesOnEntityRegion() throws Exception {
        UUID heldCaptured = UUID.randomUUID();
        UUID heldReleased = UUID.randomUUID();
        assertInstanceOf(EconomyResult.Committed.class, engine.reservations().reserve(
                EconomyReservations.Kind.REPAIR, heldCaptured, account, new Money(300), c -> true));
        assertInstanceOf(EconomyResult.Committed.class, engine.reservations().reserve(
                EconomyReservations.Kind.REPAIR, heldReleased, account, new Money(200), c -> true));
        playerPdcMap.put(new NamespacedKey("allium", "repair_receipts"), heldCaptured.toString());

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS);
             var files = mockStatic(PlayerDataFiles.class);
             var proof = mockStatic(PlayerDataReceiptProof.class)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(playerOwner);
            files.when(() -> PlayerDataFiles.directory(server)).thenReturn(data);
            proof.when(() -> PlayerDataReceiptProof.read(any(), eq(PlayerDataReceiptProof.REPAIR_RECEIPTS)))
                    .thenReturn(heldCaptured.toString());

            PaidRepairService repairs = new PaidRepairService(plugin);
            repairs.onJoin(new org.bukkit.event.player.PlayerJoinEvent(player, ""));
            repairWorkers.remove().run();
            assertEquals(1, playerTasks.size());
            playerTasks.remove().run();

            assertBalance(9_700, 0);
            assertFalse(repairs.busy(player));
            assertNull(playerPdcMap.get(new NamespacedKey("allium", "repair_receipts")));
            verify(messages).send(eq(player), eq(Messages.SIGN_REPAIR_RECOVERED),
                    eq("{captured}"), eq("1"), eq("{released}"), eq("1"));
            assertTrue(globalTasks.isEmpty());
        }
    }

    @Test
    void paidKitAndRtpSignsProceedThroughFoliaGateWithValidityRecheck() {
        codes.castled.allium.kit.KitService kitService = mock(codes.castled.allium.kit.KitService.class);
        KitsModule kitsModule = (KitsModule) plugin.getModuleManager().getModule("kits");
        when(kitsModule.kits()).thenReturn(kitService);
        SignService service = new SignService(plugin, null, paidSigns);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, player)).thenReturn(playerOwner);
            platform.when(() -> BukkitServerThread.region(eq(plugin), eq(world), anyInt(), anyInt())).thenReturn(signRegion);

            // Paid [Kit] now passes the Folia gate and delegates to KitService.claimFromSign with sign validity recheck
            SignIdentity paidKit = new SignIdentity(UUID.randomUUID(), SignIdentity.Type.KIT, "starter", false, new Money(100));
            signPdcMap.put(new NamespacedKey("allium", "sign_front"), paidKit.encode());
            service.using(clickSign());
            playerTasks.remove().run();
            verify(kitService).claimFromSign(eq(player), eq("starter"), eq(new Money(100)), any());

            // Paid [Rtp] passes the Folia gate and delegates to RtpService.useSign
            SignIdentity paidRtp = new SignIdentity(UUID.randomUUID(), SignIdentity.Type.RANDOMTELEPORT, "wild", false, new Money(100));
            signPdcMap.put(new NamespacedKey("allium", "sign_front"), paidRtp.encode());
            new SignService(plugin, null, paidSigns).using(clickSign());
            playerTasks.remove().run();
            verify(rtpService).useSign(eq(player), eq("wild"), eq(new Money(100)), any());
            verify(messages, never()).send(player, Messages.SIGN_PAID_FOLIA_UNAVAILABLE);
        }
    }

    private PlayerInteractEvent clickSign() {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.SOUTH, EquipmentSlot.HAND);
    }

    private static ItemStack mockDamagedSword() {
        ItemStack sword = mock(ItemStack.class);
        ItemStack clone = mock(ItemStack.class);
        Damageable meta = mock(Damageable.class);
        when(sword.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(sword.getItemMeta()).thenReturn(meta);
        when(meta.hasDamage()).thenReturn(true);
        java.util.concurrent.atomic.AtomicInteger clones = new java.util.concurrent.atomic.AtomicInteger();
        when(sword.clone()).thenAnswer(call -> clones.getAndIncrement() % 2 == 0 ? clone : sword);
        when(clone.setItemMeta(meta)).thenReturn(true);
        return sword;
    }

    private void assertBalance(long book, long reserved) {
        var balance = assertInstanceOf(EconomyResult.Found.class, engine.balance(account));
        assertEquals(book, balance.book().minor());
        assertEquals(reserved, balance.reserved().minor());
    }
}
