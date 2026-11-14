package codes.castled.allium.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.BukkitCommandRenderer;
import codes.castled.allium.command.items.GiveCommand;
import codes.castled.allium.command.items.ItemSelfCommand;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.moderation.ModerationRepository;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.util.AlliumLogger;
import codes.castled.allium.command.info.AfkCommand;
import codes.castled.allium.command.info.ListCommand;
import codes.castled.allium.command.info.NearCommand;
import codes.castled.allium.command.info.SeenCommand;
import codes.castled.allium.command.info.WhoisCommand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerInfoServiceAndCommandsTest {

    @TempDir
    Path tempDir;

    private AtomicLong clock;
    private PluginStart plugin;
    private Server server;
    private PluginManager pluginManager;
    private YamlConfiguration config;
    private LanguageManager languageManager;
    private MessageManager messageManager;
    private ExecutorService infoPool;
    private ExecutorService modPool;
    private ConnectionSource dbSource;
    private PlayerInfoRepository infoRepo;
    private PlayerNameRepository nameRepo;
    private ModerationRepository modRepo;
    private ModerationService moderation;
    private PlayerInfoService infoService;
    private AtomicBoolean syncSqlOnCallerThread;

    private World world;
    private FakePlayer staff;
    private FakePlayer norm;
    private FakePlayer observer;
    private ConsoleCommandSender console;
    private List<String> consoleMessages;
    private List<Player> onlinePlayers;

    @BeforeEach
    void setUp() throws Exception {
        clock = new AtomicLong(1_700_000_000_000L);
        syncSqlOnCallerThread = new AtomicBoolean(false);
        Thread testThread = Thread.currentThread();

        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        config = new YamlConfiguration();
        config.set("info.list.group-by-permission", true);
        config.set("info.list.default-group", "Players");
        config.set("info.list.groups", List.of("owner", "admin", "staff", "vip"));
        config.set("info.near.default-radius", 200);
        config.set("info.near.max-radius", 500);
        config.set("info.afk.auto-afk-seconds", 300);
        config.set("info.afk.kick-on-afk", false);
        config.set("info.afk.kick-after-seconds", 600);
        config.set("info.afk.broadcast", true);
        config.set("info.afk.ignore-sleep", true);

        languageManager = mock(LanguageManager.class);
        when(languageManager.get(any(Messages.class))).thenAnswer(inv -> ((Messages) inv.getArgument(0)).getDefault());
        when(languageManager.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        messageManager = new MessageManager(plugin, languageManager);

        AlliumLogger logger = mock(AlliumLogger.class);
        when(plugin.getConfig()).thenAnswer(inv -> config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageManager()).thenReturn(messageManager);
        when(plugin.getLanguageManager()).thenReturn(languageManager);
        when(plugin.logger()).thenReturn(logger);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("Allium", "1.0.0", "codes.castled.allium.PluginStart"));

        onlinePlayers = new CopyOnWriteArrayList<>();
        when(server.getMaxPlayers()).thenReturn(20);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getOnlinePlayers()).thenAnswer(inv -> (Collection) onlinePlayers);
        when(server.getPlayerExact(anyString())).thenAnswer(inv -> findByName(inv.getArgument(0)));
        when(server.getPlayer(anyString())).thenAnswer(inv -> findByName(inv.getArgument(0)));
        when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> findByUuid(inv.getArgument(0)));

        consoleMessages = new ArrayList<>();
        console = mock(ConsoleCommandSender.class);
        doAnswer(inv -> {
            consoleMessages.add(stripColors(inv.getArgument(0)));
            return null;
        }).when(console).sendMessage(anyString());
        when(console.hasPermission(anyString())).thenReturn(true);
        when(server.getConsoleSender()).thenReturn(console);

        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("s18-unit.db").toAbsolutePath();
        dbSource = () -> DriverManager.getConnection(dbUrl);
        infoRepo = new PlayerInfoRepository(dbSource, SqlDialect.SQLITE, clock::get);
        nameRepo = new PlayerNameRepository(dbSource, SqlDialect.SQLITE, clock::get);
        modRepo = new ModerationRepository(dbSource, SqlDialect.SQLITE, clock::get);
        infoRepo.migrate();
        nameRepo.migrate();
        modRepo.migrate();

        infoPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-playerinfo"));
        modPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-mod"));

        moderation = new ModerationService(plugin, modRepo, modPool, clock::get);
        ModerationModule modModule = mock(ModerationModule.class);
        when(modModule.isEnabled()).thenReturn(true);
        when(modModule.moderation()).thenReturn(moderation);
        ModuleManager moduleManager = mock(ModuleManager.class);
        when(moduleManager.getModule("moderation")).thenReturn(modModule);
        when(plugin.getModuleManager()).thenReturn(moduleManager);

        infoService = new PlayerInfoService(plugin, infoRepo, nameRepo, command -> infoPool.execute(() -> {
            if (Thread.currentThread() == testThread) {
                syncSqlOnCallerThread.set(true);
            }
            command.run();
        }), clock::get, VaultGroupResolver.disabled());

        moderation.enable();
        infoService.enable();

        world = mock(World.class);
        when(world.getName()).thenReturn("world");

        staff = new FakePlayer(UUID.randomUUID(), "InfoStaff", new Location(world, 100.0, 64.0, 100.0));
        norm = new FakePlayer(UUID.randomUUID(), "InfoNorm", new Location(world, 105.0, 64.0, 100.0));
        observer = new FakePlayer(UUID.randomUUID(), "InfoObs", new Location(world, 140.0, 64.0, 100.0));

        staff.grant("allium.list", "allium.seen", "allium.whois", "allium.whois.location", "allium.near", "allium.afk",
                "allium.vanish", "allium.vanish.see", "allium.list.group.staff");
        norm.grant("allium.list", "allium.seen", "allium.whois", "allium.near", "allium.afk");
        observer.grant("allium.list", "allium.seen", "allium.whois", "allium.whois.location", "allium.near", "allium.afk",
                "allium.vanish.see", "allium.list.group.vip");

        onlinePlayers.addAll(List.of(staff.mock, norm.mock, observer.mock));
        // Record joins cleanly
        for (FakePlayer fp : List.of(staff, norm, observer)) {
            moderation.onPlayerJoin(new PlayerJoinEvent(fp.mock, (String) null));
            infoService.onPlayerJoin(new PlayerJoinEvent(fp.mock, (String) null));
            nameRepo.record(fp.uuid, fp.name, clock.get());
        }
        // Extra historical name for staff
        nameRepo.record(staff.uuid, "OldStaffName", clock.get() - 50_000L);
        drainPools();
        assertFalse(syncSqlOnCallerThread.get(), "SQL must run on bounded pool, not caller thread");
    }

    @AfterEach
    void tearDown() throws Exception {
        infoService.disable();
        moderation.disable();
        PlayerNameVisibility.setVanishHooks(null, null);
        infoPool.shutdownNow();
        modPool.shutdownNow();
        infoPool.awaitTermination(2, TimeUnit.SECONDS);
        modPool.awaitTermination(2, TimeUnit.SECONDS);
    }

    private void drainPools() throws Exception {
        modPool.submit(() -> {}).get(2, TimeUnit.SECONDS);
        infoPool.submit(() -> {}).get(2, TimeUnit.SECONDS);
    }

    @Test
    void listGroupsVisiblePlayersAndHidesVanishedFromNormalViewer() throws Exception {
        ListCommand listCmd = new ListCommand(plugin, infoService);
        listCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.LIST, norm.mock, "list", new String[0]));
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("3/20")));
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("Staff (1)") && m.contains("InfoStaff")));
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("Vip (1)") && m.contains("InfoObs")));
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("Players (1)") && m.contains("InfoNorm")));

        norm.messages.clear();
        observer.messages.clear();
        moderation.setVanished(staff.mock, staff.mock, true);
        infoService.setAfk(observer.mock, true, null);
        drainPools();

        listCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.LIST, norm.mock, "list", new String[0]));
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("2/20")));
        assertFalse(norm.messages.stream().anyMatch(m -> m.contains("InfoStaff")));
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("InfoObs [AFK]")));

        listCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.LIST, observer.mock, "list", new String[0]));
        assertTrue(observer.messages.stream().anyMatch(m -> m.contains("3/20")));
        assertTrue(observer.messages.stream().anyMatch(m -> m.contains("InfoStaff [Vanished]")));
    }

    @Test
    void seenAndWhoisMatchOfflineResponseByteForByteWhenTargetIsVanished() throws Exception {
        SeenCommand seen = new SeenCommand(plugin, infoService);
        WhoisCommand whois = new WhoisCommand(plugin, infoService);

        // Staff vanishes at t0 + 10s
        clock.addAndGet(10_000L);
        long vanishAt = clock.get();
        moderation.setVanished(staff.mock, staff.mock, true);
        drainPools();

        // Advance clock by 25s -> norm inspects /seen and /whois on online-but-vanished InfoStaff
        clock.addAndGet(25_000L);
        norm.grant("allium.whois.location"); // Even with whois.location, lacking vanish.see hides location!
        seen.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SEEN, norm.mock, "seen", new String[]{"InfoStaff"}));
        whois.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.WHOIS, norm.mock, "whois", new String[]{"InfoStaff"}));
        observer.messages.clear();
        seen.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SEEN, observer.mock, "seen", new String[]{"InfoStaff"}));
        whois.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.WHOIS, observer.mock, "whois", new String[]{"InfoStaff"}));
        drainPools();

        List<String> normSeenWhileOnlineVanished = List.copyOf(norm.messages);
        norm.messages.clear();

        // Observer sees online + [Vanished] + location + name history
        assertTrue(observer.messages.stream().anyMatch(m -> m.contains("InfoStaff is online [Vanished]")));
        assertTrue(observer.messages.stream().anyMatch(m -> m.contains("Whois InfoStaff (online [Vanished]):")));
        assertTrue(observer.messages.stream().anyMatch(m -> m.contains("Location: world (100, 64, 100)")));
        assertTrue(observer.messages.stream().anyMatch(m -> m.contains("Known names: InfoStaff, OldStaffName")));

        // Normal viewer sees offline output with 25s last-seen (never "online") and NO Location line
        assertEquals(1, normSeenWhileOnlineVanished.stream().filter(m -> m.contains("is offline")).count());
        assertTrue(normSeenWhileOnlineVanished.stream().anyMatch(m -> m.contains("last seen 25s ago")));
        assertFalse(normSeenWhileOnlineVanished.stream().anyMatch(m -> m.contains("is online") || m.contains("Location:")));

        // Now staff genuinely quits while vanished (preserving vanishAt = last_seen) and restart occurs
        infoService.onPlayerQuit(new PlayerQuitEvent(staff.mock, (String) null));
        moderation.onPlayerQuit(new PlayerQuitEvent(staff.mock, (String) null));
        onlinePlayers.remove(staff.mock);
        drainPools();

        // Simulate restart
        infoService.disable();
        moderation.disable();
        moderation.enable();
        infoService.enable();
        drainPools();

        seen.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SEEN, norm.mock, "seen", new String[]{"InfoStaff"}));
        whois.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.WHOIS, norm.mock, "whois", new String[]{"InfoStaff"}));
        drainPools();

        assertEquals(normSeenWhileOnlineVanished, norm.messages,
                "/seen and /whois output for a vanished online player must be byte-identical to when genuinely offline");

        // Rejoin while vanished must also NOT advance last_seen to join time!
        clock.addAndGet(15_000L);
        onlinePlayers.add(staff.mock);
        moderation.onPlayerJoin(new PlayerJoinEvent(staff.mock, (String) null));
        infoService.onPlayerJoin(new PlayerJoinEvent(staff.mock, (String) null));
        drainPools();
        assertEquals(vanishAt, infoRepo.findByUuid(staff.uuid).orElseThrow().lastSeen());

        norm.messages.clear();
        seen.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SEEN, norm.mock, "seen", new String[]{"InfoStaff"}));
        drainPools();
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("last seen 40s ago")));
    }

    @Test
    void nearFiltersVanishSortsByDistanceAndEnforcesMaxRadius() {
        NearCommand near = new NearCommand(plugin, infoService);
        moderation.setVanished(staff.mock, staff.mock, true);
        infoService.setAfk(observer.mock, true, null);

        near.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.NEAR, norm.mock, "near", new String[]{"100"}));
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("InfoObs (35m) [AFK]") && !m.contains("InfoStaff")));

        near.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.NEAR, observer.mock, "near", new String[]{"100"}));
        assertTrue(observer.messages.stream().anyMatch(m -> m.contains("InfoNorm (35m), InfoStaff (40m) [Vanished]")));

        norm.messages.clear();
        near.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.NEAR, norm.mock, "near", new String[]{"600"}));
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("cannot exceed 500")));

        norm.grant(PlayerInfoService.PERMISSION_NEAR_BYPASS_MAX);
        norm.messages.clear();
        near.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.NEAR, norm.mock, "near", new String[]{"600"}));
        assertTrue(norm.messages.stream().anyMatch(m -> m.contains("within 600m")));
    }

    @Test
    void afkCoordinatesSleepWithVanishSuppressesVanishedBroadcastAndRespectsKickOptIn() {
        AfkCommand afk = new AfkCommand(plugin, infoService);

        // 1. Kick-on-AFK is OFF by default; vanished AFK broadcasts only to self + vanish.see + console
        moderation.setVanished(staff.mock, staff.mock, true);
        norm.messages.clear();
        observer.messages.clear();
        consoleMessages.clear();

        afk.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.AFK, staff.mock, "afk", new String[]{"&4Tea", "break"}));
        assertTrue(infoService.isAfk(staff.mock));
        assertTrue(staff.sleepingIgnored);
        assertEquals(List.of(), norm.messages, "Vanished AFK change must not broadcast to unprivileged players");
        assertTrue(observer.messages.stream().anyMatch(m -> m.contains("InfoStaff is now AFK: &4Tea break")));
        assertTrue(consoleMessages.stream().anyMatch(m -> m.contains("InfoStaff is now AFK: &4Tea break")));

        // 2. Shared sleep coordination: un-AFKing a vanished player MUST keep sleepingIgnored=true
        infoService.setAfk(staff.mock, false, null);
        assertFalse(infoService.isAfk(staff.mock));
        assertTrue(staff.sleepingIgnored, "Vanished player must remain sleepingIgnored after leaving AFK");

        // Enter AFK again, then unvanish -> sleepingIgnored still true; then move -> sleepingIgnored false
        infoService.setAfk(staff.mock, true, null);
        moderation.setVanished(staff.mock, staff.mock, false);
        assertTrue(staff.sleepingIgnored, "AFK player must remain sleepingIgnored after leaving vanish");

        infoService.onPlayerMove(new PlayerMoveEvent(
                staff.mock,
                new Location(world, 100.0, 64.0, 100.0),
                new Location(world, 102.0, 64.0, 100.0)
        ));
        assertFalse(infoService.isAfk(staff.mock));
        assertFalse(staff.sleepingIgnored);

        // 3. Auto-AFK & kick-on-AFK opt-in + exempt
        clock.addAndGet(300_000L);
        infoService.tickAfk();
        assertTrue(infoService.isAfk(norm.mock));
        assertTrue(norm.sleepingIgnored);

        // With kick-on-afk false (default), ticking far past kickAfter does NOT kick
        clock.addAndGet(700_000L);
        infoService.tickAfk();
        assertFalse(norm.kicked);

        // Opt-in kick-on-afk: true + reload
        config.set("info.afk.kick-on-afk", true);
        infoService.reloadSettings();
        observer.grant(PlayerInfoService.PERMISSION_AFK_KICK_EXEMPT);
        infoService.setAfk(observer.mock, true, null);
        clock.addAndGet(600_000L);
        infoService.tickAfk();
        assertTrue(norm.kicked, "Non-exempt AFK player must be kicked when info.afk.kick-on-afk=true");
        assertFalse(observer.kicked, "Player with allium.afk.kick.exempt must not be kicked");
    }

    @Test
    void iAndItemShareGivePermissionsAndParsingForSelfUse() {
        GiveCommand give = mock(GiveCommand.class);
        CommandFamilyPolicy policy = new CommandFamilyPolicy();
        policy.registerSpec(CoreCommandSpecs.GIVE);
        policy.registerSpec(CoreCommandSpecs.ITEM);

        BukkitCommandRenderer renderer = new BukkitCommandRenderer(plugin, policy, new ItemSelfCommand(plugin, give));
        org.bukkit.command.Command mockCmd = mock(org.bukkit.command.Command.class);

        // Normal player without allium.give is denied
        renderer.onCommand(norm.mock, mockCmd, "i", new String[]{"diamond", "4"});
        assertTrue(norm.messages.stream().anyMatch(m -> m.toLowerCase().contains("permission")));

        // Player with allium.give delegates /i and /item to GiveCommand#give targeting self
        norm.grant("allium.give");
        renderer.onCommand(norm.mock, mockCmd, "i", new String[]{"diamond", "4", "-e"});
        renderer.onCommand(norm.mock, mockCmd, "item", new String[]{"emerald", "1", "--overflow=refuse"});

        verify(give).give(norm.mock, List.of(norm.mock), "diamond 4 -e");
        verify(give).give(norm.mock, List.of(norm.mock), "emerald 1 --overflow=refuse");

        // Console rejected with console usage mentioning /give
        consoleMessages.clear();
        renderer.onCommand(console, mockCmd, "i", new String[]{"diamond"});
        assertTrue(consoleMessages.stream().anyMatch(m -> m.contains("/give <player>")));
    }

    private Player findByName(String name) {
        for (Player p : onlinePlayers) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private Player findByUuid(UUID uuid) {
        for (Player p : onlinePlayers) {
            if (p.getUniqueId().equals(uuid)) {
                return p;
            }
        }
        return null;
    }

    private static String stripColors(String input) {
        return input == null ? "" : input.replaceAll("\u00a7.", "");
    }

    private final class FakePlayer {
        final UUID uuid;
        final String name;
        final Player mock;
        final Set<String> perms = new HashSet<>();
        final List<String> messages = new ArrayList<>();
        final Map<String, MetadataValue> metadata = new HashMap<>();
        Location location;
        boolean sleepingIgnored;
        boolean kicked;

        FakePlayer(UUID uuid, String name, Location location) {
            this.uuid = uuid;
            this.name = name;
            this.location = location;
            this.mock = mock(Player.class);

            when(mock.getUniqueId()).thenReturn(uuid);
            when(mock.getName()).thenReturn(name);
            when(mock.isOnline()).thenAnswer(inv -> !kicked && onlinePlayers.contains(mock));
            when(mock.getLocation()).thenAnswer(inv -> this.location);
            when(mock.getWorld()).thenAnswer(inv -> this.location.getWorld());
            when(mock.hasPermission(anyString())).thenAnswer(inv -> perms.contains(inv.getArgument(0)));
            when(mock.isPermissionSet(anyString())).thenAnswer(inv -> perms.contains(inv.getArgument(0)));
            when(mock.canSee(any(Player.class))).thenAnswer(inv -> {
                Player target = inv.getArgument(0);
                if (target.equals(mock)) {
                    return true;
                }
                return !moderation.isVanished(target) || perms.contains("allium.vanish.see");
            });
            when(mock.isSleepingIgnored()).thenAnswer(inv -> sleepingIgnored);
            doAnswer(inv -> {
                sleepingIgnored = inv.getArgument(0);
                return null;
            }).when(mock).setSleepingIgnored(any(Boolean.class));
            doAnswer(inv -> {
                messages.add(stripColors(inv.getArgument(0)));
                return null;
            }).when(mock).sendMessage(anyString());
            doAnswer(inv -> {
                kicked = true;
                return null;
            }).when(mock).kickPlayer(anyString());
            doAnswer(inv -> {
                metadata.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(mock).setMetadata(anyString(), any(MetadataValue.class));
            doAnswer(inv -> {
                metadata.remove((String) inv.getArgument(0));
                return null;
            }).when(mock).removeMetadata(anyString(), any(Plugin.class));
            when(mock.getMetadata(anyString())).thenAnswer(inv -> {
                MetadataValue val = metadata.get((String) inv.getArgument(0));
                return val == null ? List.of() : List.of(val);
            });
            when(mock.hasMetadata(anyString())).thenAnswer(inv -> metadata.containsKey((String) inv.getArgument(0)));
        }

        void grant(String... nodes) {
            perms.addAll(List.of(nodes));
        }
    }
}
