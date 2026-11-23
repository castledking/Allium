package codes.castled.allium.alts;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.alts.AltExemptCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.sign.SignService;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AltsAndImportersFoliaTest {

    private final ArrayDeque<Runnable> workers = new ArrayDeque<>();
    private final ArrayDeque<Runnable> globalTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> staffTasks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> regionTasks = new ArrayDeque<>();

    private PluginStart plugin;
    private Server server;
    private PluginManager pm;
    private TaskScheduler scheduler;
    private MessageManager messages;
    private AltRepository repo;
    private PlayerNameRepository nameRepo;
    private PlayerNameService nameService;
    private Player joiner;
    private Player staff;
    private ServerThread staffOwner;
    private ServerThread foreignRegion;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        pm = mock(PluginManager.class);
        scheduler = mock(TaskScheduler.class);
        messages = mock(MessageManager.class);
        repo = mock(AltRepository.class);
        nameRepo = mock(PlayerNameRepository.class);
        nameService = mock(PlayerNameService.class);

        YamlConfiguration config = new YamlConfiguration();
        config.set("enabledSigns", List.of("warp"));
        when(plugin.getName()).thenReturn("Allium");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getPlayerNames()).thenReturn(nameService);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(server.getPluginManager()).thenReturn(pm);
        when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));

        when(scheduler.isServerThread()).thenReturn(false);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(c -> {
            globalTasks.add(c.getArgument(0));
            return mock(TaskHandle.class);
        });

        UUID joinerId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        joiner = mock(Player.class);
        staff = mock(Player.class);
        when(joiner.getUniqueId()).thenReturn(joinerId);
        when(joiner.getName()).thenReturn("AltPlayer");
        when(joiner.isOnline()).thenReturn(true);
        when(joiner.getAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName("203.0.113.10"), 12345));

        when(staff.getUniqueId()).thenReturn(staffId);
        when(staff.getName()).thenReturn("Mod");
        when(staff.isOnline()).thenReturn(true);
        when(staff.hasPermission(AltService.PERMISSION_ALERTS)).thenReturn(true);

        when(server.getOnlinePlayers()).thenAnswer(c -> List.of(joiner, staff));

        staffOwner = mock(ServerThread.class);
        foreignRegion = mock(ServerThread.class);
        when(staffOwner.isServerThread()).thenReturn(false);
        when(foreignRegion.isServerThread()).thenReturn(false);
        when(scheduler.runOn(eq(staffOwner), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            staffTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
        when(scheduler.runOn(eq(foreignRegion), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(c -> {
            regionTasks.add(c.getArgument(1));
            return mock(TaskHandle.class);
        });
    }

    @Test
    void altServiceRunsGroupDispatchOnGlobalAndRoutesStaffAlertsToEachStaffRegion() throws Exception {
        UUID mainId = UUID.randomUUID();
        when(repo.isAlt(joiner.getUniqueId(), "203.0.113.10")).thenReturn(true);
        when(repo.exemption(joiner.getUniqueId())).thenReturn(Optional.empty());
        when(repo.linkedAccounts(eq(joiner.getUniqueId()), anyInt())).thenReturn(List.of(mainId));
        NameCache cache = new NameCache(16);
        cache.put(mainId, "MainPlayer", 1L);
        when(nameService.cache()).thenReturn(cache);

        Plugin lp = mock(Plugin.class);
        when(lp.isEnabled()).thenReturn(true);
        when(pm.getPlugin("LuckPerms")).thenReturn(lp);
        when(server.dispatchCommand(any(), anyString())).thenReturn(true);

        AltService alts = new AltService(plugin, repo, nameRepo, workers::add);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, staff)).thenReturn(staffOwner);

            alts.onJoin(new PlayerJoinEvent(joiner, ""));
            workers.remove().run();
            assertEquals(1, globalTasks.size(), "LuckPerms console dispatch must run on global scheduler");

            UUID joinerId = joiner.getUniqueId();
            globalTasks.remove().run();
            verify(server).dispatchCommand(any(), contains("lp user " + joinerId + " parent add alt"));
            verify(messages, never()).send(eq(staff), eq(Messages.ALTS_DETECTED), any(String[].class));
            assertEquals(1, staffTasks.size(), "staff alert must hop onto staff member's owning region");

            staffTasks.remove().run();
            verify(messages).send(eq(staff), eq(Messages.ALTS_DETECTED),
                    eq("{player}"), eq("AltPlayer"), eq("{accounts}"), eq("MainPlayer"), eq("{group}"), eq("alt"));
        }
    }

    @Test
    void altExemptCommandRoutesRepliesToSenderRegionWhileRunningRemoveGroupOnGlobal() {
        UUID targetId = UUID.randomUUID();
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(targetId);
        when(server.getOfflinePlayer(targetId)).thenReturn(offline);
        when(nameService.resolve("AltPlayer")).thenReturn(
                CompletableFuture.completedFuture(Optional.of(new NameCache.Entry(targetId, "AltPlayer", 1L))));

        AltService alts = new AltService(plugin, repo, nameRepo, workers::add);
        AltExemptCommand cmd = new AltExemptCommand(plugin, alts);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, staff)).thenReturn(staffOwner);

            cmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.ALTEXEMPT, staff, "altexempt",
                    new String[]{"add", "AltPlayer", "sibling"}));

            // Name resolution completes onto sender's entity region
            assertEquals(1, staffTasks.size());
            staffTasks.remove().run();
            // Worker writes DB exemption and schedules removeGroup on global
            workers.remove().run();
            assertEquals(1, globalTasks.size());
            globalTasks.remove().run();
            // Reply hops back to sender's entity region
            assertEquals(1, staffTasks.size());
            verify(messages, never()).send(eq(staff), eq(Messages.ALTS_EXEMPT_ADDED), any(String[].class));
            staffTasks.remove().run();
            verify(messages).send(eq(staff), eq(Messages.ALTS_EXEMPT_ADDED),
                    eq("{player}"), eq("AltPlayer"), eq("{group}"), eq("alt"));
        }
    }

    @Test
    void signServiceLoadedChunkAdoptionDispatchesForeignChunksToTheirRegionOnFolia() {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(3);
        when(chunk.getZ()).thenReturn(-2);
        when(chunk.getTileEntities()).thenReturn(new BlockState[0]);
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
        when(world.isChunkLoaded(3, -2)).thenReturn(true);
        when(world.getChunkAt(3, -2)).thenReturn(chunk);
        when(server.getWorlds()).thenReturn(List.of(world));

        SignService signs = new SignService(plugin);

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.region(plugin, world, 3, -2)).thenReturn(foreignRegion);

            // Dry-run count safely skips foreign chunks without calling getTileEntities() off-region
            assertEquals(0, signs.countLoadedEssentialsSigns());
            verify(chunk, never()).getTileEntities();

            // Adoption schedules foreign chunk onto its owning chunk region
            signs.adoptLoadedChunks();
            verify(chunk, never()).getTileEntities();
            assertEquals(1, regionTasks.size());

            when(foreignRegion.isServerThread()).thenReturn(true);
            regionTasks.remove().run();
            verify(chunk).getTileEntities();
        }
    }

    @Test
    void placeholdersOnFoliaComputeLiveOnlyOnPlayerOwnerRegionAndSnapshotPerPlayerRegion() throws Exception {
        when(staff.getPlayer()).thenReturn(staff);
        when(staff.getPing()).thenReturn(42);
        var placeholders = new codes.castled.allium.placeholder.AlliumPlaceholders(plugin);
        placeholders.register("custom_key", p -> "live-" + p.getPing());

        try (var platform = mockStatic(BukkitServerThread.class, CALLS_REAL_METHODS)) {
            platform.when(BukkitServerThread::isFolia).thenReturn(true);
            platform.when(() -> BukkitServerThread.entity(plugin, staff)).thenReturn(staffOwner);
            platform.when(() -> BukkitServerThread.entity(plugin, joiner)).thenReturn(foreignRegion);

            // Off-owner thread gets snapshot (empty before refresh)
            when(staffOwner.isServerThread()).thenReturn(false);
            assertEquals("", placeholders.request(staff, "custom_key"));

            // Owning entity region computes live
            when(staffOwner.isServerThread()).thenReturn(true);
            assertEquals("live-42", placeholders.request(staff, "custom_key"));

            // Snapshot refresh dispatches per online player onto their entity region
            when(staffOwner.isServerThread()).thenReturn(false);
            when(server.getOnlinePlayers()).thenAnswer(c -> List.of(staff));
            var refresh = codes.castled.allium.placeholder.AlliumPlaceholders.class.getDeclaredMethod("refreshSnapshots");
            refresh.setAccessible(true);
            refresh.invoke(placeholders);
            assertEquals(1, staffTasks.size(), "snapshot refresh must dispatch to player's owning region");
            when(staffOwner.isServerThread()).thenReturn(true);
            staffTasks.remove().run();

            // Off-owner thread now reads the freshly populated snapshot
            when(staffOwner.isServerThread()).thenReturn(false);
            assertEquals("live-42", placeholders.request(staff, "custom_key"));
        }
    }
}
