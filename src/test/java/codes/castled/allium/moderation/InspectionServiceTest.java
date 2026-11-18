package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.moderation.EnderchestCommand;
import codes.castled.allium.command.moderation.InvseeCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InspectionServiceTest {

    @TempDir
    Path tempDir;

    private PluginStart plugin;
    private Server server;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private ConnectionSource connSource;
    private ModerationRepository repository;
    private PlayerNameService names;
    private OfflinePlayerDataStore offlineStore;
    private Executor ioExecutor;
    private FakeCodec codec;
    private InspectionService service;

    private final Queue<Runnable> serverQueue = new ArrayDeque<>();
    private final Queue<Runnable> ioQueue = new ArrayDeque<>();
    private boolean immediateIo = false;
    private BooleanSupplier refreshTask;

    private final Map<UUID, Player> online = new HashMap<>();
    private final Map<String, NameCache.Entry> directory = new HashMap<>();
    private final Map<UUID, Set<String>> playerPerms = new HashMap<>();
    private final Map<UUID, Boolean> busyPlayers = new HashMap<>();
    private final Set<UUID> hiddenTargets = new HashSet<>();
    private final Set<UUID> exemptTargets = new HashSet<>();
    private final Set<UUID> higherRankTargets = new HashSet<>();

    private static final int DATA_VERSION = 3465;

    private static final class FakeCodec implements PlayerDataCodec {
        final Map<UUID, ItemStack[]> storedInv = new HashMap<>();
        final Map<UUID, ItemStack[]> storedEc = new HashMap<>();
        boolean failSelfTest = false;
        boolean stallWrite = false;
        CountDownLatch insideWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);

        @Override
        public int currentDataVersion() {
            return DATA_VERSION;
        }

        @Override
        public void selfTest() throws IOException {
            if (failSelfTest) {
                throw new IOException("simulated codec self-test failure");
            }
        }

        @Override
        public LoadedData read(byte[] compressedNbt, InspectionLayout.InspectionType type) throws IOException {
            PlayerDataReceiptProof.Snapshot snap = PlayerDataReceiptProof.inspect(compressedNbt);
            String idStr = snap.bukkitValues().get("test:uuid");
            int len = InspectionLayout.slotCount(type);
            ItemStack[] out = new ItemStack[len];
            if (idStr != null && !idStr.isEmpty()) {
                UUID id = UUID.fromString(idStr);
                ItemStack[] src = type == InspectionLayout.InspectionType.INVSEE ? storedInv.get(id) : storedEc.get(id);
                if (src != null) {
                    System.arraycopy(src, 0, out, 0, Math.min(src.length, len));
                }
            }
            return new LoadedData(snap.dataVersion(), out);
        }

        @Override
        public byte[] write(byte[] baseCompressedNbt, InspectionLayout.InspectionType type, ItemStack[] playerSlots) throws IOException {
            if (stallWrite) {
                insideWrite.countDown();
                try {
                    assertTrue(releaseWrite.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            PlayerDataReceiptProof.Snapshot base = PlayerDataReceiptProof.inspect(baseCompressedNbt);
            String idStr = base.bukkitValues().get("test:uuid");
            if (idStr != null && !idStr.isEmpty()) {
                UUID id = UUID.fromString(idStr);
                ItemStack[] copy = new ItemStack[playerSlots.length];
                for (int i = 0; i < playerSlots.length; i++) {
                    copy[i] = playerSlots[i] == null ? null : playerSlots[i].clone();
                }
                if (type == InspectionLayout.InspectionType.INVSEE) {
                    storedInv.put(id, copy);
                } else {
                    storedEc.put(id, copy);
                }
            }
            return buildPlayerDat(base.dataVersion(), base.bukkitValues());
        }
    }

    @BeforeEach
    void setup() throws Exception {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        scheduler = mock(TaskScheduler.class);
        names = mock(PlayerNameService.class);

        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("moderation.db").toAbsolutePath();
        connSource = () -> {
            Connection c = DriverManager.getConnection(dbUrl);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000");
            }
            return c;
        };
        repository = new ModerationRepository(connSource, SqlDialect.SQLITE, System::currentTimeMillis);
        repository.migrate();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(plugin.getPlayerNames()).thenReturn(names);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getOnlinePlayers()).thenAnswer(inv -> new ArrayList<>(online.values()));
        when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> online.get(inv.<UUID>getArgument(0)));
        when(server.getPlayerExact(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            for (Player p : online.values()) {
                if (p.getName().equalsIgnoreCase(q)) return p;
            }
            return null;
        });

        when(server.createInventory(any(), anyInt(), anyString())).thenAnswer(inv ->
                createBackingInventory(inv.getArgument(0), inv.getArgument(1)));

        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(inv -> {
            serverQueue.add(inv.getArgument(0));
            return TaskHandle.NONE;
        });
        when(scheduler.repeatOnServer(any(BooleanSupplier.class), anyLong(), anyLong())).thenAnswer(inv -> {
            refreshTask = inv.getArgument(0);
            return TaskHandle.NONE;
        });

        LanguageManager language = mock(LanguageManager.class);
        for (Messages m : Messages.values()) {
            when(language.get(m)).thenReturn(m.getDefault());
        }
        messages = spy(new MessageManager(plugin, language));
        when(plugin.getMessageManager()).thenReturn(messages);
        when(names.resolve(anyString())).thenAnswer(inv ->
                CompletableFuture.completedFuture(Optional.ofNullable(directory.get(inv.<String>getArgument(0).toLowerCase()))));

        Path playerDataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerDataDir);
        codec = new FakeCodec();
        offlineStore = new OfflinePlayerDataStore(playerDataDir, codec);

        ioExecutor = cmd -> {
            if (immediateIo) {
                cmd.run();
            } else {
                ioQueue.add(cmd);
            }
        };

        service = new InspectionService(
                plugin,
                repository,
                offlineStore,
                ioExecutor,
                (sender, targetId) -> sender.hasPermission(InspectionService.PERMISSION_BYPASS)
                        || (!exemptTargets.contains(targetId) && !higherRankTargets.contains(targetId)),
                player -> Boolean.TRUE.equals(busyPlayers.get(player.getUniqueId())),
                () -> names
        );
        PlayerNameVisibility.setVanishHooks(p -> hiddenTargets.contains(p.getUniqueId()), null);
        service.enable();
    }

    @AfterEach
    void tearDown() {
        PlayerNameVisibility.setVanishHooks(null, null);
        service.disable();
    }

    private void drainQueues() {
        while (!ioQueue.isEmpty() || !serverQueue.isEmpty()) {
            while (!ioQueue.isEmpty()) ioQueue.remove().run();
            while (!serverQueue.isEmpty()) serverQueue.remove().run();
        }
    }

    private static Inventory createBackingInventory(InventoryHolder holder, int size) {
        ItemStack[] contents = new ItemStack[size];
        Inventory inv = mock(Inventory.class);
        when(inv.getHolder()).thenReturn(holder);
        when(inv.getSize()).thenReturn(size);
        when(inv.getItem(anyInt())).thenAnswer(i -> contents[i.<Integer>getArgument(0)]);
        doAnswer(i -> {
            contents[i.<Integer>getArgument(0)] = i.getArgument(1);
            return null;
        }).when(inv).setItem(anyInt(), any());
        return inv;
    }

    private final Map<UUID, int[]> heldSlots = new HashMap<>();

    private Player createPlayer(String name, String... permissions) {
        UUID id = UUID.randomUUID();
        Player p = mock(Player.class);
        Set<String> perms = new HashSet<>(List.of(permissions));
        playerPerms.put(id, perms);

        ItemStack[] storage = new ItemStack[36];
        ItemStack[] extra = new ItemStack[5]; // 0=boots(36),1=leggings(37),2=chest(38),3=helmet(39),4=offhand(40)
        PlayerInventory pInv = mock(PlayerInventory.class);
        // Held slot 8 by default, so the plain-layout cases below never touch the main-hand cell.
        int[] held = {8};
        heldSlots.put(id, held);
        when(pInv.getHeldItemSlot()).thenAnswer(i -> held[0]);
        when(pInv.getItem(anyInt())).thenAnswer(i -> {
            int s = i.getArgument(0);
            if (s < 36) return storage[s];
            return s <= 40 ? extra[s - 36] : null;
        });
        doAnswer(i -> {
            int s = i.getArgument(0);
            ItemStack v = i.getArgument(1);
            if (s < 36) storage[s] = v;
            else if (s <= 40) extra[s - 36] = v;
            return null;
        }).when(pInv).setItem(anyInt(), any());
        when(pInv.getBoots()).thenAnswer(i -> extra[0]);
        doAnswer(i -> { extra[0] = i.getArgument(0); return null; }).when(pInv).setBoots(any());
        when(pInv.getLeggings()).thenAnswer(i -> extra[1]);
        doAnswer(i -> { extra[1] = i.getArgument(0); return null; }).when(pInv).setLeggings(any());
        when(pInv.getChestplate()).thenAnswer(i -> extra[2]);
        doAnswer(i -> { extra[2] = i.getArgument(0); return null; }).when(pInv).setChestplate(any());
        when(pInv.getHelmet()).thenAnswer(i -> extra[3]);
        doAnswer(i -> { extra[3] = i.getArgument(0); return null; }).when(pInv).setHelmet(any());
        when(pInv.getItemInOffHand()).thenAnswer(i -> extra[4]);
        doAnswer(i -> { extra[4] = i.getArgument(0); return null; }).when(pInv).setItemInOffHand(any());

        Inventory ec = createBackingInventory(p, 27);
        Inventory bottomInv = createBackingInventory(p, 36);
        AtomicReference<Inventory> openTop = new AtomicReference<>(null);
        InventoryView openView = mock(InventoryView.class);
        when(openView.getTopInventory()).thenAnswer(i -> openTop.get());
        when(openView.getBottomInventory()).thenReturn(bottomInv);
        when(openView.getPlayer()).thenReturn(p);

        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenAnswer(i -> online.get(id) == p);
        when(p.isDead()).thenReturn(false);
        when(p.hasPermission(anyString())).thenAnswer(i -> perms.contains(i.<String>getArgument(0)));
        when(p.canSee(any(Player.class))).thenAnswer(i -> !hiddenTargets.contains(i.<Player>getArgument(0).getUniqueId()));
        when(p.getInventory()).thenReturn(pInv);
        when(p.getEnderChest()).thenReturn(ec);
        when(p.getOpenInventory()).thenReturn(openView);
        doAnswer(i -> {
            openTop.set(i.getArgument(0));
            return openView;
        }).when(p).openInventory(any(Inventory.class));
        doAnswer(i -> {
            if (openTop.get() != null) {
                service.onInventoryClose(new InventoryCloseEvent(openView));
                openTop.set(null);
            }
            return null;
        }).when(p).closeInventory();

        online.put(id, p);
        directory.put(name.toLowerCase(), new NameCache.Entry(id, name, System.currentTimeMillis()));
        return p;
    }

    private static ItemStack item(Material mat, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(mat);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.isSimilar(any())).thenAnswer(i -> {
            ItemStack other = i.getArgument(0);
            return other != null && other.getType() == mat;
        });
        when(stack.clone()).thenAnswer(i -> item(mat, amount));
        return stack;
    }

    private InventoryClickEvent click(Player viewer, Inventory top, int rawSlot, InventoryAction action, ClickType clickType) {
        InventoryView view = viewer.getOpenInventory();
        InventoryType.SlotType slotType = InventoryType.SlotType.CONTAINER;
        return new InventoryClickEvent(view, slotType, rawSlot, clickType, action);
    }

    @Test
    void pluginYmlKeepsEditAndOfflineOutsideAlliumWildcard() throws Exception {
        YamlConfiguration pluginYml = new YamlConfiguration();
        pluginYml.options().pathSeparator('/');
        try (var stream = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(stream);
            pluginYml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        assertNotNull(pluginYml.getConfigurationSection("commands/invsee"));
        assertNotNull(pluginYml.getConfigurationSection("commands/enderchest"));
        assertEquals(List.of("echest", "ec"), pluginYml.getStringList("commands/enderchest/aliases"));

        var wildcard = pluginYml.getConfigurationSection("permissions/allium.*/children");
        assertNotNull(wildcard);
        assertTrue(wildcard.getBoolean("allium.invsee"));
        assertTrue(wildcard.getBoolean("allium.enderchest"));
        assertTrue(wildcard.getBoolean("allium.enderchest.others"));

        for (String privileged : List.of(
                "allium.invsee.edit", "allium.invsee.offline",
                "allium.enderchest.edit", "allium.enderchest.offline")) {
            assertFalse(wildcard.contains(privileged), "Must NOT be inside allium.*: " + privileged);
            assertEquals("false", pluginYml.getString("permissions/" + privileged + "/default"));
        }
        assertEquals("op", pluginYml.getString("permissions/allium.invsee/default"));
        assertEquals("op", pluginYml.getString("permissions/allium.enderchest/default"));
        assertEquals("op", pluginYml.getString("permissions/allium.enderchest.others/default"));
    }

    @Test
    void dispatchEnforcesPermissionsSelfRulesExemptAndVanishParity() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        new InvseeCommand(plugin, service).execute(new BukkitCommandContext(plugin, CoreCommandSpecs.INVSEE, console, "invsee", new String[]{"Alice"}));
        verify(messages).send(console, Messages.PLAYER_ONLY);

        Player viewer = createPlayer("Staff", InspectionService.PERMISSION_INVSEE, InspectionService.PERMISSION_ENDERCHEST);
        Player target = createPlayer("Alice");

        // Self invsee refused; self enderchest opens own ec
        service.dispatchInvsee(viewer, new String[]{"Staff"});
        verify(messages).send(viewer, Messages.INSPECT_SELF);

        service.dispatchEnderchest(viewer, new String[0]);
        verify(viewer).openInventory(viewer.getEnderChest());

        // /enderchest <other> requires .others
        service.dispatchEnderchest(viewer, new String[]{"Alice"});
        verify(messages).send(viewer, Messages.NO_PERMISSION);

        // Exempt target blocked unless allium.moderation.bypass
        exemptTargets.add(target.getUniqueId());
        service.dispatchInvsee(viewer, new String[]{"Alice"});
        drainQueues();
        verify(messages).send(viewer, Messages.DISCIPLINE_EXEMPT);

        // With bypass -> opens read-only
        playerPerms.get(viewer.getUniqueId()).add(InspectionService.PERMISSION_BYPASS);
        service.dispatchInvsee(viewer, new String[]{"Alice"});
        drainQueues();
        assertTrue(viewer.getOpenInventory().getTopInventory().getHolder() instanceof InspectionService.InspectionHolder h && !h.editable());
        viewer.closeInventory();
        exemptTargets.remove(target.getUniqueId());

        // Vanish blindness parity: hidden online player behaves identically to offline player!
        Player vanished = createPlayer("Ghost");
        hiddenTargets.add(vanished.getUniqueId());
        UUID offlineUuid = UUID.randomUUID();
        directory.put("offlinebob", new NameCache.Entry(offlineUuid, "OfflineBob", 1L));

        Player noOfflinePermViewer = createPlayer("Mod", InspectionService.PERMISSION_INVSEE);
        service.dispatchInvsee(noOfflinePermViewer, new String[]{"Ghost"});
        drainQueues();
        service.dispatchInvsee(noOfflinePermViewer, new String[]{"OfflineBob"});
        drainQueues();
        verify(messages, times(2)).send(noOfflinePermViewer, Messages.INSPECT_OFFLINE_REQUIRED, "{permission}", InspectionService.PERMISSION_INVSEE_OFFLINE);
        verify(noOfflinePermViewer, never()).openInventory(any(Inventory.class));

        // Even WITH .offline + bypass, hidden vanished online target CANNOT open live or .dat -> INSPECT_OFFLINE_UNAVAILABLE (same as missing .dat)
        Player fullViewer = createPlayer("Admin", InspectionService.PERMISSION_INVSEE, InspectionService.PERMISSION_INVSEE_OFFLINE, InspectionService.PERMISSION_BYPASS);
        service.dispatchInvsee(fullViewer, new String[]{"Ghost"});
        drainQueues();
        service.dispatchInvsee(fullViewer, new String[]{"UnknownPlayer"});
        drainQueues();
        verify(messages).send(fullViewer, Messages.INSPECT_OFFLINE_UNAVAILABLE, "{player}", "Ghost");
        verify(messages).send(fullViewer, Messages.INSPECT_OFFLINE_UNAVAILABLE, "{player}", "UnknownPlayer");
        verify(fullViewer, never()).openInventory(any(Inventory.class));
    }

    @Test
    void readOnlyConfinementAndLockedSlotsAreStrictlyEnforced() {
        Player viewer = createPlayer("ReadOnlyMod", InspectionService.PERMISSION_INVSEE);
        Player target = createPlayer("Target");
        target.getInventory().setItem(0, item(Material.DIAMOND, 4));
        target.getInventory().setHelmet(item(Material.IRON_HELMET, 1));
        target.getInventory().setItemInOffHand(item(Material.SHIELD, 1));

        service.dispatchInvsee(viewer, new String[]{"Target"});
        drainQueues();

        Inventory gui = viewer.getOpenInventory().getTopInventory();
        assertEquals(Material.DIAMOND, gui.getItem(27).getType()); // hotbar[0] -> GUI 27
        assertEquals(Material.IRON_HELMET, gui.getItem(36).getType()); // helmet(39) -> GUI 36
        assertEquals(Material.SHIELD, gui.getItem(40).getType()); // offhand(40) -> GUI 40
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, gui.getItem(41).getType());

        // Top slot click cancelled in read-only
        InventoryClickEvent topClick = click(viewer, gui, 27, InventoryAction.PICKUP_ALL, ClickType.LEFT);
        service.onInventoryClick(topClick);
        assertTrue(topClick.isCancelled());

        // Shift-click from bottom cancelled in read-only
        InventoryClickEvent shiftBottom = click(viewer, gui, 45, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT);
        service.onInventoryClick(shiftBottom);
        assertTrue(shiftBottom.isCancelled());

        // Collect-to-cursor cancelled in read-only
        InventoryClickEvent collect = click(viewer, gui, 45, InventoryAction.COLLECT_TO_CURSOR, ClickType.DOUBLE_CLICK);
        service.onInventoryClick(collect);
        assertTrue(collect.isCancelled());

        // Bottom normal click allowed in read-only
        InventoryClickEvent bottomNormal = click(viewer, gui, 45, InventoryAction.PICKUP_ALL, ClickType.LEFT);
        service.onInventoryClick(bottomNormal);
        assertFalse(bottomNormal.isCancelled());

        // Drag touching top cancelled in read-only
        InventoryDragEvent drag = new InventoryDragEvent(viewer.getOpenInventory(), null, item(Material.STONE, 1), false, Map.of(27, item(Material.STONE, 1)));
        service.onInventoryDrag(drag);
        assertTrue(drag.isCancelled());
    }

    @Test
    void theMainHandCellShowsAndEditsTheHeldHotbarSlot() {
        Player viewer = createPlayer("HandMod", InspectionService.PERMISSION_INVSEE, InspectionService.PERMISSION_INVSEE_EDIT);
        Player target = createPlayer("Holder");
        heldSlots.get(target.getUniqueId())[0] = 2;
        target.getInventory().setItem(2, item(Material.GOLDEN_APPLE, 3));
        target.getInventory().setItem(0, item(Material.DIAMOND, 1));

        service.dispatchInvsee(viewer, new String[]{"Holder"});
        drainQueues();
        Inventory gui = viewer.getOpenInventory().getTopInventory();
        assertEquals(Material.GOLDEN_APPLE, gui.getItem(InspectionLayout.MAIN_HAND_GUI_SLOT).getType(), "the held item is in the main-hand cell");
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, gui.getItem(29).getType(), "its hotbar cell is a marker");
        assertEquals(Material.DIAMOND, gui.getItem(27).getType(), "other hotbar cells are unchanged");

        InventoryClickEvent marker = click(viewer, gui, 29, InventoryAction.PICKUP_ALL, ClickType.LEFT);
        service.onInventoryClick(marker);
        assertTrue(marker.isCancelled(), "the marker cannot be clicked");

        InventoryClickEvent hand = click(viewer, gui, InspectionLayout.MAIN_HAND_GUI_SLOT, InventoryAction.PICKUP_ALL, ClickType.LEFT);
        service.onInventoryClick(hand);
        assertFalse(hand.isCancelled(), "the main hand is editable");
        gui.setItem(InspectionLayout.MAIN_HAND_GUI_SLOT, item(Material.EMERALD, 7));
        drainQueues();
        assertEquals(Material.EMERALD, target.getInventory().getItem(2).getType(), "editing the main hand edits the held hotbar slot");
        assertEquals(7, target.getInventory().getItem(2).getAmount());
        assertEquals(Material.DIAMOND, target.getInventory().getItem(0).getType());
    }

    @Test
    void editableOnlineSyncFlushTargetGuardsQuitFlushDeathDedupeAndBusyLock() throws Exception {
        Player viewer = createPlayer("EditMod", InspectionService.PERMISSION_INVSEE, InspectionService.PERMISSION_INVSEE_EDIT);
        Player secondEditor = createPlayer("EditMod2", InspectionService.PERMISSION_INVSEE, InspectionService.PERMISSION_INVSEE_EDIT);
        Player target = createPlayer("Victim");
        target.getInventory().setItem(0, item(Material.DIAMOND_SWORD, 1));

        // Busy mail/kit blocks opening
        busyPlayers.put(target.getUniqueId(), true);
        service.dispatchInvsee(viewer, new String[]{"Victim"});
        drainQueues();
        verify(messages).send(viewer, Messages.INSPECT_BUSY);
        busyPlayers.remove(target.getUniqueId());

        // Open editable
        service.dispatchInvsee(viewer, new String[]{"Victim"});
        drainQueues();
        assertTrue(service.hasActiveMutableSession(target.getUniqueId(), InspectionLayout.InspectionType.INVSEE));

        // Concurrent second editor blocked; read-only viewer allowed
        service.dispatchInvsee(secondEditor, new String[]{"Victim"});
        drainQueues();
        verify(messages).send(secondEditor, Messages.INSPECT_BUSY);
        Player reader = createPlayer("Observer", InspectionService.PERMISSION_INVSEE);
        service.dispatchInvsee(reader, new String[]{"Victim"});
        drainQueues();
        assertNotNull(reader.getOpenInventory().getTopInventory());
        reader.closeInventory();

        Inventory gui = viewer.getOpenInventory().getTopInventory();

        // Locked slot 41 blocked even for editable viewer
        InventoryClickEvent lockedClick = click(viewer, gui, 41, InventoryAction.PICKUP_ALL, ClickType.LEFT);
        service.onInventoryClick(lockedClick);
        assertTrue(lockedClick.isCancelled());

        // Valid editable click in GUI 27 (hotbar 0): schedules 1-tick flush & activates target guards
        InventoryClickEvent editClick = click(viewer, gui, 27, InventoryAction.PICKUP_ALL, ClickType.LEFT);
        service.onInventoryClick(editClick);
        assertFalse(editClick.isCancelled());

        // While guard is active before next-tick flush, target drop/swap/pickup/consume/held are cancelled
        PlayerDropItemEvent targetDrop = mock(PlayerDropItemEvent.class);
        when(targetDrop.getPlayer()).thenReturn(target);
        service.onTargetDrop(targetDrop);
        verify(targetDrop).setCancelled(true);

        PlayerSwapHandItemsEvent targetSwap = mock(PlayerSwapHandItemsEvent.class);
        when(targetSwap.getPlayer()).thenReturn(target);
        service.onTargetSwap(targetSwap);
        verify(targetSwap).setCancelled(true);

        PlayerItemHeldEvent targetHeld = new PlayerItemHeldEvent(target, 0, 1);
        service.onTargetHeld(targetHeld);
        assertTrue(targetHeld.isCancelled());

        // Simulate Bukkit removing the item from GUI slot 27 and replacing with emerald
        gui.setItem(27, item(Material.EMERALD, 5));

        // Drain server tick -> flush applies delta to target's inventory and releases guard!
        drainQueues();
        assertEquals(Material.EMERALD, target.getInventory().getItem(0).getType());
        assertEquals(5, target.getInventory().getItem(0).getAmount());

        PlayerItemHeldEvent targetHeldAfter = new PlayerItemHeldEvent(target, 1, 2);
        service.onTargetHeld(targetHeldAfter);
        assertFalse(targetHeldAfter.isCancelled());

        // Same-tick death drop dedupe: click takes diamond_sword equivalent (take EMERALD 5 from GUI 27), target dies BEFORE flush runs
        InventoryClickEvent takeClick = click(viewer, gui, 27, InventoryAction.PICKUP_ALL, ClickType.LEFT);
        service.onInventoryClick(takeClick);
        gui.setItem(27, null);
        List<ItemStack> drops = new ArrayList<>(List.of(item(Material.EMERALD, 5), item(Material.APPLE, 2)));
        PlayerDeathEvent deathEvent = mock(PlayerDeathEvent.class);
        when(deathEvent.getEntity()).thenReturn(target);
        when(deathEvent.getDrops()).thenReturn(drops);
        service.onPlayerDeath(deathEvent);
        assertEquals(1, drops.size());
        assertEquals(Material.APPLE, drops.get(0).getType());
        verify(messages).send(viewer, Messages.INSPECT_TARGET_DIED, "{player}", "Victim");

        drainQueues();
        // Verify SQLite audit rows persisted
        List<ModerationAuditRecord> audit = repository.loadRecentAudit(10);
        assertTrue(audit.stream().anyMatch(r -> r.action().equals("INVSEE_OPEN")));
        assertTrue(audit.stream().anyMatch(r -> r.action().equals("INVSEE_EDIT")));
        assertTrue(audit.stream().anyMatch(r -> r.action().equals("INVSEE_ABORT")));
    }

    @Test
    void quitFlushesPendingOnlineEditBeforeSave() {
        Player viewer = createPlayer("EditMod", InspectionService.PERMISSION_INVSEE, InspectionService.PERMISSION_INVSEE_EDIT);
        Player target = createPlayer("Leaver");
        target.getInventory().setItem(0, item(Material.GOLD_INGOT, 8));

        service.dispatchInvsee(viewer, new String[]{"Leaver"});
        drainQueues();
        Inventory gui = viewer.getOpenInventory().getTopInventory();

        InventoryClickEvent editClick = click(viewer, gui, 27, InventoryAction.PICKUP_ALL, ClickType.LEFT);
        service.onInventoryClick(editClick);
        gui.setItem(27, null);

        // Target quits on the same tick before 1-tick flush ran -> onPlayerQuit(LOWEST) flushes synchronously!
        service.onPlayerQuit(new PlayerQuitEvent(target, "left"));
        assertNull(target.getInventory().getItem(0));
        verify(messages).send(viewer, Messages.INSPECT_TARGET_OFFLINE, "{player}", "Leaver");
    }

    @Test
    void offlineInspectionEditLoginFenceAndCasConflict() throws Exception {
        UUID offlineUuid = UUID.randomUUID();
        String offlineName = "Sleepy";
        directory.put("sleepy", new NameCache.Entry(offlineUuid, offlineName, 1L));
        ItemStack[] initialSlots = new ItemStack[InspectionLayout.ENDERCHEST_GUI_SIZE];
        initialSlots[0] = item(Material.DIAMOND, 2);
        codec.storedEc.put(offlineUuid, initialSlots);
        Map<String, String> bukkit = Map.of(
                "test:uuid", offlineUuid.toString(),
                PlayerDataReceiptProof.MAIL_RECEIPTS, "S/mail-proof",
                PlayerDataReceiptProof.KIT_RECEIPTS, "K/kit-proof"
        );
        byte[] initialBytes = buildPlayerDat(DATA_VERSION, bukkit);
        Path datFile = offlineStore.resolvePlayerFile(offlineUuid);
        Files.write(datFile, initialBytes);

        Player admin = createPlayer("Admin",
                InspectionService.PERMISSION_ENDERCHEST,
                InspectionService.PERMISSION_ENDERCHEST_OTHERS,
                InspectionService.PERMISSION_ENDERCHEST_EDIT,
                InspectionService.PERMISSION_ENDERCHEST_OFFLINE,
                InspectionService.PERMISSION_BYPASS);

        new EnderchestCommand(plugin, service).execute(new BukkitCommandContext(
                plugin, CoreCommandSpecs.ENDERCHEST, admin, "ec", new String[]{"Sleepy"}));
        drainQueues();

        Inventory gui = admin.getOpenInventory().getTopInventory();
        assertEquals(Material.DIAMOND, gui.getItem(0).getType());

        // Edit slot 1 and flush via IO
        InventoryClickEvent click = click(admin, gui, 1, InventoryAction.PLACE_ALL, ClickType.LEFT);
        service.onInventoryClick(click);
        gui.setItem(1, item(Material.NETHERITE_INGOT, 1));
        drainQueues();
        assertEquals(Material.NETHERITE_INGOT, codec.storedEc.get(offlineUuid)[1].getType());
        assertEquals(bukkit, PlayerDataReceiptProof.inspect(Files.readAllBytes(datFile)).bukkitValues());

        // Start a slow write, then simulate target login via AsyncPlayerPreLoginEvent -> fence waits for save & aborts session!
        immediateIo = false;
        InventoryClickEvent click2 = click(admin, gui, 2, InventoryAction.PLACE_ALL, ClickType.LEFT);
        service.onInventoryClick(click2);
        gui.setItem(2, item(Material.EMERALD, 1));

        // Drain server tick so flushOfflineSession starts IO task, with write stalled
        codec.stallWrite = true;
        while (!serverQueue.isEmpty()) serverQueue.remove().run();
        Thread ioWorker = new Thread(() -> {
            while (!ioQueue.isEmpty()) ioQueue.remove().run();
        });
        ioWorker.start();
        assertTrue(codec.insideWrite.await(5, TimeUnit.SECONDS));

        AsyncPlayerPreLoginEvent preLogin = new AsyncPlayerPreLoginEvent(offlineName, InetAddress.getLoopbackAddress(), offlineUuid);
        CountDownLatch preLoginDone = new CountDownLatch(1);
        Thread loginThread = new Thread(() -> {
            service.onPreLogin(preLogin);
            preLoginDone.countDown();
        });
        loginThread.start();

        // Drain the server task scheduled by onPreLogin so abortSession + loginFlushFuture are wired
        long deadline = System.currentTimeMillis() + 3000;
        while (serverQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        while (!serverQueue.isEmpty()) serverQueue.remove().run();
        assertEquals(1, preLoginDone.getCount(), "Login must stay fenced while write is in-flight");

        // Unblock write and complete server callback
        codec.releaseWrite.countDown();
        ioWorker.join(3000);
        while (serverQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        while (!serverQueue.isEmpty()) serverQueue.remove().run();

        assertTrue(preLoginDone.await(3, TimeUnit.SECONDS));
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin.getLoginResult());
        assertEquals(Material.EMERALD, codec.storedEc.get(offlineUuid)[2].getType());
        verify(messages).send(admin, Messages.INSPECT_TARGET_ONLINE, "{player}", offlineName);
    }

    private static byte[] buildPlayerDat(int dataVersion, Map<String, String> bukkitValues) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos);
             DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(3);
            out.writeUTF("DataVersion");
            out.writeInt(dataVersion);
            if (!bukkitValues.isEmpty()) {
                out.writeByte(10);
                out.writeUTF("BukkitValues");
                for (Map.Entry<String, String> entry : bukkitValues.entrySet()) {
                    out.writeByte(8);
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
                out.writeByte(0);
            }
            out.writeByte(0);
        }
        return baos.toByteArray();
    }

    @Test
    void startupSelfTestFailureDisablesOfflineEditingAndLogsLoudly() throws Exception {
        FakeCodec failingCodec = new FakeCodec();
        failingCodec.failSelfTest = true;
        OfflinePlayerDataStore failingStore = new OfflinePlayerDataStore(tempDir.resolve("playerdata-fail"), failingCodec);
        Files.createDirectories(tempDir.resolve("playerdata-fail"));

        InspectionService guardedService = new InspectionService(
                plugin,
                repository,
                failingStore,
                ioExecutor,
                (sender, id) -> true,
                p -> false,
                () -> names
        );
        guardedService.enable();
        try {
            assertFalse(guardedService.isOfflineEditingEnabled(), "Failed codec self-test must disable offline editing");
            verify(plugin.logger(), atLeastOnce()).severe(contains("startup self-test FAILED"));

            UUID offlineUuid = UUID.randomUUID();
            directory.put("offlineguy", new NameCache.Entry(offlineUuid, "OfflineGuy", 1L));
            byte[] initialBytes = buildPlayerDat(DATA_VERSION, Map.of("test:uuid", offlineUuid.toString()));
            Files.write(failingStore.resolvePlayerFile(offlineUuid), initialBytes);

            Player admin = createPlayer("Admin",
                    InspectionService.PERMISSION_INVSEE,
                    InspectionService.PERMISSION_INVSEE_EDIT,
                    InspectionService.PERMISSION_INVSEE_OFFLINE,
                    InspectionService.PERMISSION_BYPASS);
            guardedService.dispatchInvsee(admin, new String[]{"OfflineGuy"});
            drainQueues();

            verify(messages).send(admin, Messages.INSPECT_OFFLINE_EDIT_DISABLED);
            verify(admin, never()).openInventory(any(Inventory.class));

            // Online inspection must still work even when offline editing is disabled
            Player onlineTarget = createPlayer("OnlineGuy");
            guardedService.dispatchInvsee(admin, new String[]{"OnlineGuy"});
            drainQueues();
            assertNotNull(admin.getOpenInventory());
        } finally {
            guardedService.disable();
        }
    }

    @Test
    void offlineEditPreservesKSAndUnknownReceiptsAndRefusesPendingHoldAndDataVersionRewrite() throws Exception {
        UUID offlineUuid = UUID.randomUUID();
        UUID mailSender = UUID.randomUUID();
        String name = "ReceiptHolder";
        directory.put("receiptholder", new NameCache.Entry(offlineUuid, name, 1L));
        ItemStack[] initialInv = new ItemStack[InspectionLayout.slotCount(InspectionLayout.InspectionType.INVSEE)];
        initialInv[0] = item(Material.DIAMOND, 3);
        codec.storedInv.put(offlineUuid, initialInv);

        // Real K/S/C/UNKNOWN tokens in mail_receipts, kit_receipts, and an unknown future marker
        String complexMailReceipt = "S/" + mailSender + ";C/" + mailSender + "/" + offlineUuid + "/1,2;FUTURE/mail-token";
        String complexKitReceipt = "K/" + offlineUuid + "/" + mailSender + "/starter;FUTURE/kit-token";
        Map<String, String> bukkit = new LinkedHashMap<>();
        bukkit.put("test:uuid", offlineUuid.toString());
        bukkit.put(PlayerDataReceiptProof.MAIL_RECEIPTS, complexMailReceipt);
        bukkit.put(PlayerDataReceiptProof.KIT_RECEIPTS, complexKitReceipt);
        bukkit.put("unknownmod:custom_marker", "v9:opaque-marker");

        Path datFile = offlineStore.resolvePlayerFile(offlineUuid);
        Files.write(datFile, buildPlayerDat(DATA_VERSION, bukkit));
        byte[] beforeHoldBytes = Files.readAllBytes(datFile);

        // Create real workflow hold tables in SQLite and insert active HELD reservation
        try (Connection c = connSource.connection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS allium_economy_reservations (id TEXT PRIMARY KEY, account_uuid TEXT NOT NULL, state TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS allium_mail (id INTEGER PRIMARY KEY, sender TEXT NOT NULL, recipient TEXT NOT NULL, state TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS allium_kit_deliveries (player_uuid TEXT NOT NULL)");
            st.execute("INSERT INTO allium_economy_reservations (id, account_uuid, state) VALUES ('res-1', '" + offlineUuid + "', 'HELD')");
        }

        // Editable offline open must be refused while a hold is pending, preserving receipt & file verbatim
        Player editor = createPlayer("Editor",
                InspectionService.PERMISSION_INVSEE,
                InspectionService.PERMISSION_INVSEE_EDIT,
                InspectionService.PERMISSION_INVSEE_OFFLINE,
                InspectionService.PERMISSION_BYPASS);
        service.dispatchInvsee(editor, new String[]{name});
        drainQueues();
        verify(messages).send(editor, Messages.INSPECT_OFFLINE_HOLD, "{player}", name);
        verify(editor, never()).openInventory(any(Inventory.class));
        assertArrayEquals(beforeHoldBytes, Files.readAllBytes(datFile));

        // Read-Only offline inspection MUST still succeed while hold is pending
        Player reader = createPlayer("Reader",
                InspectionService.PERMISSION_INVSEE,
                InspectionService.PERMISSION_INVSEE_OFFLINE,
                InspectionService.PERMISSION_BYPASS);
        service.dispatchInvsee(reader, new String[]{name});
        drainQueues();
        assertNotNull(reader.getOpenInventory());
        reader.closeInventory();
        drainQueues();

        // Transition reservation out of HELD and verify mail PENDING & kit delivery holds too
        try (Connection c = connSource.connection(); Statement st = c.createStatement()) {
            st.execute("UPDATE allium_economy_reservations SET state = 'COMMITTED' WHERE id = 'res-1'");
            st.execute("INSERT INTO allium_mail (sender, recipient, state) VALUES ('" + offlineUuid + "', '" + mailSender + "', 'PENDING')");
        }
        assertTrue(repository.hasPendingWorkflowHold(offlineUuid));
        try (Connection c = connSource.connection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM allium_mail");
            st.execute("INSERT INTO allium_kit_deliveries (player_uuid) VALUES ('" + offlineUuid + "')");
        }
        assertTrue(repository.hasPendingWorkflowHold(offlineUuid));
        try (Connection c = connSource.connection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM allium_kit_deliveries");
        }
        assertFalse(repository.hasPendingWorkflowHold(offlineUuid));

        // Now offline edit succeeds and preserves K/S/C/FUTURE receipts + unknown marker verbatim
        service.dispatchInvsee(editor, new String[]{name});
        drainQueues();
        Inventory gui = editor.getOpenInventory().getTopInventory();
        InventoryClickEvent editClick = click(editor, gui, 28, InventoryAction.PLACE_ALL, ClickType.LEFT);
        service.onInventoryClick(editClick);
        gui.setItem(28, item(Material.EMERALD, 6));
        drainQueues();

        assertEquals(Material.EMERALD, codec.storedInv.get(offlineUuid)[1].getType());
        assertEquals(bukkit, PlayerDataReceiptProof.inspect(Files.readAllBytes(datFile)).bukkitValues());
        editor.closeInventory();
        drainQueues();

        // Older DataVersion file cannot be rewritten by offline edit -> Read-Only opens, .edit refused, file untouched
        byte[] oldVersionBytes = buildPlayerDat(DATA_VERSION - 1, bukkit);
        Files.write(datFile, oldVersionBytes);

        service.dispatchInvsee(reader, new String[]{name});
        drainQueues();
        assertNotNull(reader.getOpenInventory());
        reader.closeInventory();
        drainQueues();

        service.dispatchInvsee(editor, new String[]{name});
        drainQueues();
        verify(messages).send(editor, Messages.INSPECT_OFFLINE_VERSION_REWRITE, "{player}", name);
        verify(editor, times(1)).openInventory(any(Inventory.class));
        assertArrayEquals(oldVersionBytes, Files.readAllBytes(datFile));
    }
}

