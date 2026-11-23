package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.discord.StaffFeeds;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.text.FormattedText;
import codes.castled.allium.text.TextSender;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationPartAFoliaTest {

    private static final class FoliaHarness {
        final PluginStart plugin = mock(PluginStart.class);
        final Server server = mock(Server.class);
        final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        final TaskScheduler scheduler = mock(TaskScheduler.class);
        final MessageManager messages = mock(MessageManager.class);
        final PlayerNameService names = mock(PlayerNameService.class);
        final ModerationRepository repository = mock(ModerationRepository.class);
        final YamlConfiguration config = new YamlConfiguration();
        final World world = mock(World.class);
        final AtomicReference<ServerThread> currentRegion = new AtomicReference<>();
        final AtomicBoolean onGlobal = new AtomicBoolean(false);
        final Map<Player, ServerThread> playerRegions = new HashMap<>();
        final Map<ServerThread, Queue<Runnable>> regionQueues = new HashMap<>();
        final Queue<Runnable> globalQueue = new ArrayDeque<>();
        final Queue<Runnable> ioQueue = new ArrayDeque<>();
        final Map<Player, Set<String>> perms = new HashMap<>();

        FoliaHarness() throws Exception {
            when(world.getName()).thenReturn("world");
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getScheduler()).thenReturn(scheduler);
            when(plugin.getMessageManager()).thenReturn(messages);
            when(plugin.getPlayerNames()).thenReturn(names);
            when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
            when(server.getConsoleSender()).thenReturn(console);
            when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            when(repository.loadVanishedPlayers()).thenReturn(Set.of());
            when(repository.loadActiveMutes()).thenReturn(List.of());
            when(messages.resolve(any(Messages.class), any(String[].class))).thenAnswer(inv -> {
                Messages m = inv.getArgument(0);
                String out = m.getDefault();
                Object[] raw = inv.getArguments();
                for (int i = 1; i + 1 < raw.length; i += 2) {
                    out = out.replace(String.valueOf(raw[i]), String.valueOf(raw[i + 1]));
                }
                return out;
            });
            when(messages.resolve(any(Messages.class))).thenAnswer(inv -> ((Messages) inv.getArgument(0)).getDefault());

            when(scheduler.isServerThread()).thenAnswer(inv -> onGlobal.get());
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
        }

        Player player(String name) {
            Player p = mock(Player.class);
            UUID id = UUID.randomUUID();
            ServerThread region = mock(ServerThread.class, name + "-region");
            when(region.isServerThread()).thenAnswer(inv -> currentRegion.get() == region);
            playerRegions.put(p, region);
            regionQueues.put(region, new ArrayDeque<>());

            when(p.getUniqueId()).thenReturn(id);
            when(p.getName()).thenReturn(name);
            when(p.isOnline()).thenReturn(true);
            when(p.getServer()).thenReturn(server);
            when(p.getMetadata("vanished")).thenReturn(List.of());
            when(p.getLocation()).thenAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".getLocation() called off owning region");
                return new Location(world, 10, 64, 20);
            });
            when(p.getNearbyEntities(anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".getNearbyEntities() called off owning region");
                return List.of();
            });
            doAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".setSleepingIgnored() called off owning region");
                return null;
            }).when(p).setSleepingIgnored(anyBoolean());
            doAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".hidePlayer() called off owning region");
                return null;
            }).when(p).hidePlayer(eq(plugin), any(Player.class));
            doAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".showPlayer() called off owning region");
                return null;
            }).when(p).showPlayer(eq(plugin), any(Player.class));
            when(p.canSee(any(Player.class))).thenAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".canSee() called off owning region");
                return true;
            });
            when(p.performCommand(anyString())).thenAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".performCommand() called off owning region");
                return true;
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
            boolean prevGlobal = onGlobal.getAndSet(false);
            try {
                body.run();
            } finally {
                currentRegion.set(prev);
                onGlobal.set(prevGlobal);
            }
        }

        int drainRegion(Player p) {
            ServerThread region = regionOf(p);
            Queue<Runnable> queue = regionQueues.get(region);
            int count = 0;
            ServerThread prev = currentRegion.getAndSet(region);
            boolean prevGlobal = onGlobal.getAndSet(false);
            try {
                while (!queue.isEmpty()) {
                    queue.poll().run();
                    count++;
                }
            } finally {
                currentRegion.set(prev);
                onGlobal.set(prevGlobal);
            }
            return count;
        }

        int drainGlobal() {
            int count = 0;
            ServerThread prev = currentRegion.getAndSet(null);
            boolean prevGlobal = onGlobal.getAndSet(true);
            try {
                while (!globalQueue.isEmpty()) {
                    globalQueue.poll().run();
                    count++;
                }
            } finally {
                currentRegion.set(prev);
                onGlobal.set(prevGlobal);
            }
            return count;
        }

        MockedStatic<BukkitServerThread> mockFolia() {
            MockedStatic<BukkitServerThread> mocked = mockStatic(BukkitServerThread.class, Answers.CALLS_REAL_METHODS);
            mocked.when(BukkitServerThread::isFolia).thenReturn(true);
            mocked.when(() -> BukkitServerThread.entity(eq(plugin), any(Player.class)))
                    .thenAnswer(inv -> playerRegions.get(inv.getArgument(1)));
            return mocked;
        }
    }

    @Test
    void vanishOtherRoutesTargetStateAndVisibilityPairsToRespectiveEntityRegions() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player admin = h.player("Admin");
        Player target = h.player("Target");
        Player viewer = h.player("Viewer");
        Player staffViewer = h.player("StaffViewer");
        h.perms.get(staffViewer).add(ModerationService.PERMISSION_VANISH_SEE);
        doAnswer(inv -> List.of(admin, target, viewer, staffViewer)).when(h.server).getOnlinePlayers();

        AtomicBoolean targetNotifiedOnOwnRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(target), h.currentRegion.get(), "VANISH_ON notice must run on Target's region");
            targetNotifiedOnOwnRegion.set(true);
            return null;
        }).when(h.messages).send(eq(target), eq(Messages.VANISH_ON));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            ModerationService service = new ModerationService(h.plugin, h.repository, h.ioQueue::add, () -> 1_000L);
            service.enable();
            h.drainRegion(admin);
            h.drainRegion(target);
            h.drainRegion(viewer);
            h.drainRegion(staffViewer);

            h.runInRegion(admin, () -> service.setVanished(admin, target, true));

            assertFalse(targetNotifiedOnOwnRegion.get(), "target vanish state and notice wait for Target's region");
            h.drainRegion(target);
            h.drainRegion(viewer);
            h.drainRegion(staffViewer);

            assertTrue(targetNotifiedOnOwnRegion.get());
            verify(target, org.mockito.Mockito.atLeastOnce()).setSleepingIgnored(true);
            verify(viewer).hidePlayer(h.plugin, target);
            verify(staffViewer, org.mockito.Mockito.atLeastOnce()).showPlayer(h.plugin, target);
        }
    }

    @Test
    void muteAndAsyncChatBlockRouteNotificationsToTargetsEntityRegion() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player admin = h.player("Admin");
        Player bob = h.player("Bob");
        UUID bobId = bob.getUniqueId();
        doAnswer(inv -> List.of(admin, bob)).when(h.server).getOnlinePlayers();
        when(h.server.getPlayer(bobId)).thenReturn(bob);

        AtomicBoolean muteNoticeOnBobRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(bob), h.currentRegion.get(), "MUTE_TARGET_NOTICE must run on Bob's region");
            muteNoticeOnBobRegion.set(true);
            return null;
        }).when(h.messages).send(eq(bob), eq(Messages.MUTE_TARGET_NOTICE), any(String[].class));

        AtomicBoolean blockedNoticeOnBobRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(bob), h.currentRegion.get(), "MUTE_BLOCKED from async chat must run on Bob's region");
            blockedNoticeOnBobRegion.set(true);
            return null;
        }).when(h.messages).send(eq(bob), eq(Messages.MUTE_BLOCKED), any(String[].class));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            ModerationService service = new ModerationService(h.plugin, h.repository, h.ioQueue::add, () -> 1_000L);
            service.enable();
            h.drainRegion(admin);
            h.drainRegion(bob);

            h.runInRegion(admin, () -> service.mute(admin, bobId, "Bob", 60_000L, "spam"));
            assertFalse(muteNoticeOnBobRegion.get());
            h.drainRegion(bob);
            assertTrue(muteNoticeOnBobRegion.get());

            // Async chat fires off-region (currentRegion == null).
            h.currentRegion.set(null);
            AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(true, bob, "hi", new HashSet<>(Set.of(admin, bob)));
            service.onAsyncPlayerChat(chat);
            assertTrue(chat.isCancelled());
            assertFalse(blockedNoticeOnBobRegion.get(), "blocked notice must not run directly on async chat thread");

            h.drainRegion(bob);
            assertTrue(blockedNoticeOnBobRegion.get());
        }
    }

    @Test
    void commandSpyRoutesToObserverRegionAndSpectateDispatchesConsoleCommandsOnGlobal() throws Exception {
        FoliaHarness h = new FoliaHarness();
        h.config.set("spectate-commands", List.of("tp {target}", "player:vanish on"));
        Player actor = h.player("Actor");
        Player observer = h.player("Observer");
        h.perms.get(observer).add(StaffSpyService.PERM_COMMANDSPY);
        doAnswer(inv -> List.of(actor, observer)).when(h.server).getOnlinePlayers();

        AtomicBoolean spyDeliveredOnObserverRegion = new AtomicBoolean(false);
        AtomicBoolean consoleDispatchedOnGlobal = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertTrue(h.onGlobal.get(), "console dispatch in /spectate must run on global tick thread");
            assertEquals("tp Observer Actor", inv.getArgument(1));
            consoleDispatchedOnGlobal.set(true);
            return true;
        }).when(h.server).dispatchCommand(eq(h.console), anyString());

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia();
             MockedStatic<TextSender> textSender = mockStatic(TextSender.class)) {
            textSender.when(() -> TextSender.sendLiteral(eq(observer), any(FormattedText.class))).thenAnswer(inv -> {
                assertEquals(h.regionOf(observer), h.currentRegion.get(), "command spy message must run on observer's region");
                spyDeliveredOnObserverRegion.set(true);
                return null;
            });

            StaffSpyService spy = new StaffSpyService(h.plugin);
            h.runInRegion(observer, () -> spy.setGlobalCommandSpy(observer, true));

            // Actor runs command in Actor's region -> Observer's canSee + sendLiteral happen in Observer's region.
            h.runInRegion(actor, () -> spy.onCommand(new PlayerCommandPreprocessEvent(actor, "/home base")));
            assertFalse(spyDeliveredOnObserverRegion.get());
            assertEquals(1, h.drainRegion(observer));
            assertTrue(spyDeliveredOnObserverRegion.get());

            // Observer spectates Actor from Observer's region -> console command runs on global, player: runs on Observer's region.
            h.runInRegion(observer, () -> spy.spectate(observer, actor));
            verify(observer).performCommand("vanish on");
            assertFalse(consoleDispatchedOnGlobal.get(), "console command waits for global scheduler");
            assertEquals(1, h.drainGlobal());
            assertTrue(consoleDispatchedOnGlobal.get());
        }
    }

    @Test
    void reportReturnsToReporterRegionForLocationAndAlertsStaffOnStaffRegions() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player reporter = h.player("Reporter");
        Player staff = h.player("Staff");
        h.perms.get(staff).add(ReportService.PERM_RECEIVE);
        doAnswer(inv -> List.of(reporter, staff)).when(h.server).getOnlinePlayers();

        UUID targetId = UUID.randomUUID();
        var resolvedTarget = CompletableFuture.completedFuture(Optional.of(new NameCache.Entry(targetId, "Griefer", 1_000L)));
        when(h.names.resolve("Griefer")).thenReturn(resolvedTarget);

        AtomicBoolean staffAlertOnOwnRegion = new AtomicBoolean(false);
        AtomicBoolean staffSoundOnOwnRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(staff), h.currentRegion.get(), "report alert sound must run on staff's region");
            staffSoundOnOwnRegion.set(true);
            return null;
        }).when(h.messages).playSoundIfPresent(eq(staff), eq(Messages.REPORT_STAFF));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia();
             MockedStatic<TextSender> textSender = mockStatic(TextSender.class)) {
            textSender.when(() -> TextSender.sendLiteral(eq(staff), any(FormattedText.class))).thenAnswer(inv -> {
                assertEquals(h.regionOf(staff), h.currentRegion.get(), "report alert text must run on staff's region");
                staffAlertOnOwnRegion.set(true);
                return null;
            });

            ReportService reports = new ReportService(h.plugin, h.repository, mock(StaffFeeds.class), h.ioQueue::add, () -> 1_000L);
            h.runInRegion(reporter, () -> reports.report(reporter, new String[]{"Griefer", "broke", "spawn"}, () -> {}));

            assertTrue(h.globalQueue.isEmpty(), "report resolution must return to reporter's region, not global");
            assertEquals(1, h.drainRegion(reporter));
            assertFalse(staffAlertOnOwnRegion.get(), "staff alert waits for staff's region");

            assertEquals(1, h.drainRegion(staff));
            assertTrue(staffAlertOnOwnRegion.get());
            assertTrue(staffSoundOnOwnRegion.get());
            verify(h.messages).send(eq(reporter), eq(Messages.REPORT_SENT));
        }
    }
}
