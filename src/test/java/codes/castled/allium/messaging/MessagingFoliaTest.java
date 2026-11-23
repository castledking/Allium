package codes.castled.allium.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.messaging.MsgToggleCommand;
import codes.castled.allium.command.messaging.SocialSpyCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.preference.StoredPreferences;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.text.FormattedText;
import codes.castled.allium.text.TextSender;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingFoliaTest {

    private static final class FoliaHarness {
        final PluginStart plugin = mock(PluginStart.class);
        final Server server = mock(Server.class);
        final TaskScheduler scheduler = mock(TaskScheduler.class);
        final ModuleManager modules = mock(ModuleManager.class);
        final PreferencesModule preferences = mock(PreferencesModule.class);
        final PreferenceSessions sessions = mock(PreferenceSessions.class);
        final LanguageManager language = mock(LanguageManager.class);
        final MessageManager messageManager = mock(MessageManager.class);
        final YamlConfiguration config = new YamlConfiguration();
        final AtomicReference<ServerThread> currentRegion = new AtomicReference<>();
        final Map<Player, ServerThread> playerRegions = new HashMap<>();
        final Map<ServerThread, Queue<Runnable>> regionQueues = new HashMap<>();
        final Queue<Runnable> globalQueue = new ArrayDeque<>();

        FoliaHarness() {
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getScheduler()).thenReturn(scheduler);
            when(plugin.getModuleManager()).thenReturn(modules);
            when(plugin.getLanguageManager()).thenReturn(language);
            when(plugin.getMessageManager()).thenReturn(messageManager);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.logger()).thenReturn(mock(AlliumLogger.class));

            when(modules.getModule("preferences")).thenReturn(preferences);
            when(preferences.isEnabled()).thenReturn(true);
            when(preferences.sessions()).thenReturn(sessions);
            when(preferences.acceptance(any(UUID.class), eq(PreferenceKey.ACCEPT_MESSAGES)))
                    .thenReturn(PreferenceSessions.Acceptance.ACCEPTS);
            when(language.get(any(Messages.class))).thenAnswer(inv -> ((Messages) inv.getArgument(0)).getDefault());

            when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(inv -> {
                globalQueue.add(inv.getArgument(0));
                return TaskHandle.NONE;
            });
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
            when(p.getMetadata("vanished")).thenReturn(List.of());
            when(p.canSee(any(Player.class))).thenAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".canSee() called off owning region");
                return true;
            });
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

        int drainRegion(Player p) {
            ServerThread region = regionOf(p);
            Queue<Runnable> queue = regionQueues.get(region);
            int count = 0;
            ServerThread prev = currentRegion.getAndSet(region);
            try {
                while (!queue.isEmpty()) {
                    queue.poll().run();
                    count++;
                }
            } finally {
                currentRegion.set(prev);
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
    void socialSpyEvaluatesCanSeeAndDeliversOnSpyEntityRegion() {
        FoliaHarness h = new FoliaHarness();
        MessageManager realMessages = new MessageManager(h.plugin, h.language);
        when(h.plugin.getMessageManager()).thenReturn(realMessages);

        Player alice = h.player("Alice");
        Player bob = h.player("Bob");
        Player spy = h.player("Watcher");
        UUID spyId = spy.getUniqueId();
        when(spy.hasPermission("allium.socialspy")).thenReturn(true);
        when(h.preferences.effective(spyId)).thenReturn(
                Optional.of(new EffectivePreferences(true, true, true, 0.5D, false, true)));
        doAnswer(inv -> List.of(alice, bob, spy)).when(h.server).getOnlinePlayers();

        IgnoreService ignores = mock(IgnoreService.class);
        when(ignores.ignores(any(UUID.class), any(UUID.class))).thenReturn(MessagePolicy.Ignore.NO);

        AtomicBoolean bobDeliveredOnOwnRegion = new AtomicBoolean(false);
        AtomicBoolean spyDeliveredOnOwnRegion = new AtomicBoolean(false);

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia();
             MockedStatic<TextSender> textSender = mockStatic(TextSender.class)) {
            textSender.when(() -> TextSender.sendLiteral(any(), any(FormattedText.class))).thenAnswer(inv -> {
                Object target = inv.getArgument(0);
                if (target == bob) {
                    assertEquals(h.regionOf(bob), h.currentRegion.get(), "recipient delivery must run on Bob's region");
                    bobDeliveredOnOwnRegion.set(true);
                } else if (target == spy) {
                    assertEquals(h.regionOf(spy), h.currentRegion.get(), "social spy delivery must run on Spy's region");
                    spyDeliveredOnOwnRegion.set(true);
                }
                return null;
            });

            MessagingService service = new MessagingService(h.plugin, ignores);
            // Alice sends on Alice's region: Spy.canSee() must NOT be called on Alice's region!
            h.runInRegion(alice, () -> {
                MessagePolicy.Outcome outcome = service.send(alice, bob, "Bob", "secret hello");
                assertEquals(MessagePolicy.Outcome.DELIVER, outcome);
            });

            assertFalse(bobDeliveredOnOwnRegion.get(), "cross-region recipient delivery waits for Bob's region");
            assertFalse(spyDeliveredOnOwnRegion.get(), "cross-region spy visibility check + delivery waits for Spy's region");

            assertEquals(1, h.drainRegion(bob));
            assertEquals(1, h.drainRegion(spy));
            assertTrue(bobDeliveredOnOwnRegion.get());
            assertTrue(spyDeliveredOnOwnRegion.get());
        }
    }

    @Test
    void msgToggleForOtherRoutesTargetNotificationToTargetsRegion() {
        FoliaHarness h = new FoliaHarness();
        Player admin = h.player("Admin");
        Player bob = h.player("Bob");
        UUID bobId = bob.getUniqueId();
        when(admin.hasPermission(CoreCommandSpecs.MSGTOGGLE.othersPermission())).thenReturn(true);
        when(h.preferences.effective(bobId)).thenReturn(
                Optional.of(new EffectivePreferences(true, true, true, 1.0D, false, false)));
        when(h.sessions.change(eq(bobId), any(PreferenceChange.class)))
                .thenReturn(CompletableFuture.completedFuture(StoredPreferences.empty(bobId)));

        MessagingService messaging = mock(MessagingService.class);
        when(messaging.find(admin, "Bob")).thenReturn(bob);
        when(messaging.visibleTo(admin, bob)).thenReturn(true);

        AtomicBoolean bobNotifiedOnOwnRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(bob), h.currentRegion.get(), "target notification must run on Bob's region");
            bobNotifiedOnOwnRegion.set(true);
            return null;
        }).when(h.messageManager).send(eq(bob), eq(Messages.MSGTOGGLE_OFF));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            MsgToggleCommand command = new MsgToggleCommand(h.plugin, messaging);
            var ctx = new BukkitCommandContext(h.plugin, CoreCommandSpecs.MSGTOGGLE, admin, "msgtoggle", new String[]{"off", "Bob"});

            h.runInRegion(admin, () -> command.execute(ctx));

            verify(h.messageManager).send(admin, Messages.MSGTOGGLE_OTHER_OFF, "{player}", "Bob");
            assertFalse(bobNotifiedOnOwnRegion.get(), "target notice must wait for Bob's region");

            assertEquals(1, h.drainRegion(bob));
            assertTrue(bobNotifiedOnOwnRegion.get());
        }
    }

    @Test
    void socialSpySaveFailureRoutesNoticeToPlayerRegion() {
        FoliaHarness h = new FoliaHarness();
        Player spy = h.player("Spy");
        UUID spyId = spy.getUniqueId();
        when(h.preferences.effective(spyId)).thenReturn(
                Optional.of(new EffectivePreferences(true, true, true, 1.0D, false, false)));
        CompletableFuture<StoredPreferences> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("db down"));
        when(h.sessions.change(eq(spyId), any(PreferenceChange.class))).thenReturn(failed);

        AtomicBoolean failedNoticeOnOwnRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(spy), h.currentRegion.get(), "save-failed notice must run on Spy's region");
            failedNoticeOnOwnRegion.set(true);
            return null;
        }).when(h.messageManager).send(eq(spy), eq(Messages.SOCIALSPY_SAVE_FAILED));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            SocialSpyCommand command = new SocialSpyCommand(h.plugin);
            var ctx = new BukkitCommandContext(h.plugin, CoreCommandSpecs.SOCIALSPY, spy, "socialspy", new String[]{"on"});

            h.runInRegion(spy, () -> command.execute(ctx));

            assertTrue(h.globalQueue.isEmpty(), "save-failed notice must not be scheduled on global thread");
            assertEquals(1, h.drainRegion(spy));
            assertTrue(failedNoticeOnOwnRegion.get());
        }
    }

    @Test
    void ignoreWriteFailureRollsBackAndNotifiesOwnerOnEntityRegion() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player alice = h.player("Alice");
        UUID aliceId = alice.getUniqueId();
        UUID targetId = UUID.randomUUID();
        when(h.server.getPlayer(aliceId)).thenReturn(alice);

        IgnoreRepository repository = mock(IgnoreRepository.class);
        when(repository.load(aliceId)).thenReturn(Set.of());
        when(repository.add(aliceId, targetId)).thenThrow(new SQLException("disk full"));

        ThreadPoolExecutor directExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };

        IgnoreService service = new IgnoreService(h.plugin, repository, directExecutor);
        service.onPreLogin(new AsyncPlayerPreLoginEvent("Alice", InetAddress.getLoopbackAddress(), aliceId));

        AtomicBoolean callbackOnAliceRegion = new AtomicBoolean(false);
        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia();
             MockedStatic<Bukkit> ignoredBukkit = mockStatic(Bukkit.class)) {
            IgnoreService.Change change = service.add(aliceId, targetId, ok -> {
                assertFalse(ok);
                assertEquals(h.regionOf(alice), h.currentRegion.get(), "ignore callback must run on Alice's region");
                callbackOnAliceRegion.set(true);
            });

            assertEquals(IgnoreService.Change.DONE, change);
            assertEquals(MessagePolicy.Ignore.NO, service.ignores(aliceId, targetId), "failed write must be undone");
            assertTrue(h.globalQueue.isEmpty(), "callback for online player must use entity region, not global");

            assertEquals(1, h.drainRegion(alice));
            assertTrue(callbackOnAliceRegion.get());
        } finally {
            directExecutor.shutdownNow();
        }
    }
}
