package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisciplineFoliaTest {

    private static final class FoliaHarness {
        final PluginStart plugin = mock(PluginStart.class);
        final Server server = mock(Server.class);
        final TaskScheduler scheduler = mock(TaskScheduler.class);
        final ModerationRepository repository = mock(ModerationRepository.class);
        final PlayerNameService names = mock(PlayerNameService.class);
        final YamlConfiguration config = new YamlConfiguration();
        final World world = mock(World.class);
        final UUID worldId = UUID.randomUUID();
        final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        final AtomicReference<ServerThread> currentRegion = new AtomicReference<>();
        final Map<Player, ServerThread> playerRegions = new HashMap<>();
        final Map<String, ServerThread> chunkRegions = new HashMap<>();
        final Map<ServerThread, Queue<Runnable>> regionQueues = new HashMap<>();
        final Queue<Runnable> globalQueue = new ArrayDeque<>();
        final Queue<Runnable> ioQueue = new ArrayDeque<>();
        final Map<UUID, Player> online = new HashMap<>();
        final Map<String, NameCache.Entry> directory = new HashMap<>();
        final Map<Player, Set<String>> perms = new HashMap<>();
        final Map<UUID, JailAssignment> persistedPrisoners = new HashMap<>();
        final List<JailRecord> persistedJails = new ArrayList<>();

        FoliaHarness() throws Exception {
            config.set("moderation.preload-wait-millis", 0);
            config.set("moderation.discipline.rate-limit-millis", 0);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getScheduler()).thenReturn(scheduler);
            when(plugin.getPlayerNames()).thenReturn(names);
            when(plugin.logger()).thenReturn(mock(AlliumLogger.class));

            when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            when(server.getOnlinePlayers()).thenAnswer(inv -> new ArrayList<>(online.values()));
            when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> online.get(inv.getArgument(0)));
            when(server.getWorld(worldId)).thenReturn(world);

            LanguageManager language = mock(LanguageManager.class);
            for (Messages m : Messages.values()) {
                when(language.get(m)).thenReturn(m.getDefault());
            }
            when(plugin.getMessageManager()).thenReturn(new MessageManager(plugin, language));
            when(names.resolve(anyString())).thenAnswer(inv -> CompletableFuture.completedFuture(
                    Optional.ofNullable(directory.get(inv.<String>getArgument(0).toLowerCase()))));

            when(repository.loadActiveBans()).thenReturn(List.of());
            when(repository.loadActiveBan(any())).thenReturn(Optional.empty());
            when(repository.loadFrozenPlayers()).thenReturn(List.of());
            when(repository.loadFrozen(any())).thenReturn(Optional.empty());
            when(repository.loadJailedPlayers()).thenAnswer(inv -> List.copyOf(persistedPrisoners.values()));
            when(repository.loadJailed(any())).thenAnswer(inv -> Optional.ofNullable(persistedPrisoners.get(inv.getArgument(0))));
            when(repository.loadJails()).thenAnswer(inv -> List.copyOf(persistedJails));
            when(repository.setJailed(any())).thenAnswer(inv -> {
                JailAssignment j = inv.getArgument(0);
                persistedPrisoners.put(j.playerUuid(), j);
                return true;
            });
            when(repository.removeJailed(any(), anyString())).thenAnswer(inv -> persistedPrisoners.remove(inv.getArgument(0)) != null);

            when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(inv -> {
                globalQueue.add(inv.getArgument(0));
                return TaskHandle.NONE;
            });
            when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);
            when(scheduler.runOn(any(ServerThread.class), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(inv -> {
                ServerThread region = inv.getArgument(0);
                Runnable task = inv.getArgument(1);
                regionQueues.computeIfAbsent(region, ignored -> new ArrayDeque<>()).add(task);
                return TaskHandle.NONE;
            });

            when(world.getUID()).thenReturn(worldId);
            when(world.getName()).thenReturn("world");
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            WorldBorder border = mock(WorldBorder.class);
            when(world.getWorldBorder()).thenReturn(border);
            when(border.getCenter()).thenReturn(new Location(world, 0, 64, 0));
            when(border.getSize()).thenReturn(60_000_000.0);
            when(border.isInside(any())).thenReturn(true);

            Block floor = mock(Block.class);
            Block air = mock(Block.class);
            BlockData sturdy = mock(BlockData.class);
            when(sturdy.isFaceSturdy(any(), any())).thenReturn(true);
            when(floor.getBlockData()).thenReturn(sturdy);
            when(floor.getType()).thenReturn(Material.STONE);
            when(floor.isPassable()).thenReturn(false);
            when(air.getType()).thenReturn(Material.AIR);
            when(air.isPassable()).thenReturn(true);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
                int x = inv.getArgument(0);
                int y = inv.getArgument(1);
                int z = inv.getArgument(2);
                ServerThread expectedChunkRegion = chunkRegion(x >> 4, z >> 4);
                assertEquals(expectedChunkRegion, currentRegion.get(),
                        "world.getBlockAt(" + x + "," + y + "," + z + ") called off owning chunk region");
                return y < 64 ? floor : air;
            });
        }

        ServerThread chunkRegion(int cx, int cz) {
            String key = cx + ":" + cz;
            return chunkRegions.computeIfAbsent(key, k -> {
                ServerThread region = mock(ServerThread.class, "chunk-" + k);
                when(region.isServerThread()).thenAnswer(inv -> currentRegion.get() == region);
                regionQueues.put(region, new ArrayDeque<>());
                return region;
            });
        }

        Player player(String name, double x, double z) {
            Player p = mock(Player.class);
            UUID id = UUID.randomUUID();
            ServerThread region = mock(ServerThread.class, name + "-region");
            when(region.isServerThread()).thenAnswer(inv -> currentRegion.get() == region);
            playerRegions.put(p, region);
            regionQueues.put(region, new ArrayDeque<>());
            online.put(id, p);
            directory.put(name.toLowerCase(), new NameCache.Entry(id, name, clock.get()));

            when(p.getUniqueId()).thenReturn(id);
            when(p.getName()).thenReturn(name);
            when(p.isOnline()).thenReturn(true);
            when(p.getMetadata("vanished")).thenReturn(List.of());
            when(p.getLocation()).thenAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".getLocation() called off owning region");
                return new Location(world, x, 64, z);
            });
            doAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".kickPlayer() called off owning region");
                return null;
            }).when(p).kickPlayer(anyString());
            when(p.leaveVehicle()).thenAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".leaveVehicle() called off owning region");
                return true;
            });
            when(p.teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class))).thenAnswer(inv -> {
                throw new AssertionError("sync teleport() must never be called on Folia");
            });

            Set<String> set = new HashSet<>();
            perms.put(p, set);
            when(p.hasPermission(anyString())).thenAnswer(inv -> set.contains(inv.getArgument(0)));
            return p;
        }

        ServerThread regionOf(Player p) {
            return playerRegions.get(p);
        }

        void runInRegion(Player p, Runnable body) {
            ServerThread prev = currentRegion.getAndSet(regionOf(p));
            try {
                body.run();
            } finally {
                currentRegion.set(prev);
            }
        }

        void drainAll() {
            int guard = 200;
            boolean progressed;
            do {
                progressed = false;
                while (!ioQueue.isEmpty()) {
                    ioQueue.poll().run();
                    progressed = true;
                }
                while (!globalQueue.isEmpty()) {
                    ServerThread prev = currentRegion.getAndSet(null);
                    try {
                        globalQueue.poll().run();
                    } finally {
                        currentRegion.set(prev);
                    }
                    progressed = true;
                }
                for (var entry : List.copyOf(regionQueues.entrySet())) {
                    Queue<Runnable> q = entry.getValue();
                    if (!q.isEmpty()) {
                        ServerThread prev = currentRegion.getAndSet(entry.getKey());
                        try {
                            while (!q.isEmpty()) {
                                q.poll().run();
                                progressed = true;
                            }
                        } finally {
                            currentRegion.set(prev);
                        }
                    }
                }
            } while (progressed && --guard > 0);
            assertTrue(guard > 0, "queue draining did not settle");
        }

        int drainRegion(ServerThread region) {
            Queue<Runnable> q = regionQueues.get(region);
            int count = 0;
            ServerThread prev = currentRegion.getAndSet(region);
            try {
                while (!q.isEmpty()) {
                    q.poll().run();
                    count++;
                }
            } finally {
                currentRegion.set(prev);
            }
            return count;
        }

        MockedStatic<BukkitServerThread> mockFolia(AtomicReference<CompletableFuture<Boolean>> teleportFutureRef,
                                                   AtomicReference<Location> teleportDestRef) {
            MockedStatic<BukkitServerThread> mocked = mockStatic(BukkitServerThread.class, Answers.CALLS_REAL_METHODS);
            mocked.when(BukkitServerThread::isFolia).thenReturn(true);
            mocked.when(() -> BukkitServerThread.entity(eq(plugin), any(Player.class)))
                    .thenAnswer(inv -> playerRegions.get(inv.getArgument(1)));
            mocked.when(() -> BukkitServerThread.region(eq(plugin), eq(world), anyInt(), anyInt()))
                    .thenAnswer(inv -> chunkRegion(inv.getArgument(2), inv.getArgument(3)));
            mocked.when(() -> BukkitServerThread.loadRegionalChunk(eq(world), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
            mocked.when(() -> BukkitServerThread.teleportRegional(any(Player.class), any(Location.class), any()))
                    .thenAnswer(inv -> {
                        Player p = inv.getArgument(0);
                        assertEquals(regionOf(p), currentRegion.get(), "teleportRegional must be initiated on player's region");
                        if (teleportDestRef != null) teleportDestRef.set(inv.getArgument(1));
                        return teleportFutureRef != null && teleportFutureRef.get() != null
                                ? teleportFutureRef.get()
                                : CompletableFuture.completedFuture(true);
                    });
            return mocked;
        }
    }

    @Test
    void kickFreezeAndKickAllRouteEntityMutationsToTargetsRegions() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player admin = h.player("Admin", 0, 0);
        Player bob = h.player("Bob", 500, 0);
        Player carol = h.player("Carol", 1000, 0);
        h.perms.get(admin).addAll(Set.of("allium.kick", "allium.kickall", "allium.freeze", DisciplineService.BYPASS_PERMISSION));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia(null, null)) {
            DisciplineService service = new DisciplineService(h.plugin, h.repository, h.ioQueue::add, h.clock::get);
            service.enable();
            h.drainAll();

            // /freeze Bob -> leaveVehicle runs on Bob's region.
            h.runInRegion(admin, () -> service.dispatch(admin, "freeze", new String[]{"Bob", "check"}));
            h.drainAll();
            assertTrue(service.isFrozen(bob.getUniqueId()));
            verify(bob).leaveVehicle();

            // /kick Bob -> kickPlayer runs on Bob's region.
            h.runInRegion(admin, () -> service.dispatch(admin, "kick", new String[]{"Bob", "grief"}));
            h.drainAll();
            verify(bob).kickPlayer(anyString());

            // /kickall -> kickPlayer runs on Carol's region (and Bob's).
            h.runInRegion(admin, () -> service.dispatch(admin, "kickall", new String[]{"maintenance"}));
            h.drainAll();
            verify(carol).kickPlayer(anyString());

            service.disable();
        }
    }

    @Test
    void crossRegionJailAndUnjailUseRegionalSafetyAndTeleportRegional() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player admin = h.player("Admin", 0, 0);
        Player bob = h.player("Bob", 100.5, 100.5);
        h.perms.get(admin).addAll(Set.of("allium.jail", "allium.unjail", DisciplineService.BYPASS_PERMISSION));

        SavedLocation cellLoc = new SavedLocation(h.worldId, "world", 500.5, 64, 500.5, 0f, 0f);
        h.persistedJails.add(new JailRecord("cell", cellLoc));

        AtomicReference<CompletableFuture<Boolean>> teleportFuture = new AtomicReference<>(new CompletableFuture<>());
        AtomicReference<Location> teleportDest = new AtomicReference<>();

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia(teleportFuture, teleportDest)) {
            DisciplineService service = new DisciplineService(h.plugin, h.repository, h.ioQueue::add, h.clock::get);
            service.enable();
            h.drainAll();

            // 1. /jail Bob cell grief -> captures Bob's origin on Bob's region, checks cell safety on chunk (31,31),
            //    persists assignment, and calls teleportRegional on Bob's region.
            h.runInRegion(admin, () -> service.dispatch(admin, "jail", new String[]{"Bob", "cell", "grief"}));
            h.drainAll();

            assertTrue(service.jailed(bob.getUniqueId()));
            assertNotNull(teleportDest.get());
            assertEquals(500.5, teleportDest.get().getX());

            // ScopedMove lets the matching regional teleport pass onTeleport, while blocking an unrelated teleport.
            PlayerTeleportEvent allowed = new PlayerTeleportEvent(bob, new Location(h.world, 100.5, 64, 100.5),
                    teleportDest.get().clone(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            service.onTeleport(allowed);
            assertFalse(allowed.isCancelled(), "scoped jail teleport must be permitted while in flight");

            PlayerTeleportEvent escape = new PlayerTeleportEvent(bob, new Location(h.world, 100.5, 64, 100.5),
                    new Location(h.world, 0, 64, 0), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
            service.onTeleport(escape);
            assertTrue(escape.isCancelled(), "unrelated teleport must remain blocked");

            teleportFuture.get().complete(true);
            h.drainAll();

            // 2. /unjail Bob -> resolves origin chunk safety regionally and proves teleportRegional succeeds
            //    BEFORE removing the durable jail assignment.
            CompletableFuture<Boolean> returnTeleport = new CompletableFuture<>();
            teleportFuture.set(returnTeleport);
            teleportDest.set(null);

            h.runInRegion(admin, () -> service.dispatch(admin, "unjail", new String[]{"Bob"}));
            h.drainAll();

            assertNotNull(teleportDest.get());
            assertEquals(100.5, teleportDest.get().getX());
            assertTrue(service.jailed(bob.getUniqueId()), "durable jail assignment must remain until return teleport succeeds");
            verify(h.repository, never()).removeJailed(eq(bob.getUniqueId()), anyString());

            returnTeleport.complete(true);
            h.drainAll();

            assertFalse(service.jailed(bob.getUniqueId()), "jail assignment removed once return teleport succeeded");
            verify(h.repository).removeJailed(eq(bob.getUniqueId()), eq("Admin"));

            service.disable();
        }
    }
}
