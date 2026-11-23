package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.MessageSoundSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.moderation.StaffSpyService;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatFoliaTest {

    private record Scheduled(Runnable run, Runnable retired) {}

    private static final class FoliaHarness {
        final PluginStart plugin = mock(PluginStart.class);
        final Server server = mock(Server.class);
        final TaskScheduler scheduler = mock(TaskScheduler.class);
        final MessageManager messages = mock(MessageManager.class);
        final PlayerNameService names = mock(PlayerNameService.class);
        final ChatRepository repository = mock(ChatRepository.class);
        final YamlConfiguration config = new YamlConfiguration();
        final World world = mock(World.class);
        final World otherWorld = mock(World.class);
        final AtomicReference<ServerThread> currentRegion = new AtomicReference<>();
        final Map<Player, ServerThread> playerRegions = new HashMap<>();
        final Map<ServerThread, Queue<Scheduled>> regionQueues = new HashMap<>();
        final Queue<Runnable> globalQueue = new ArrayDeque<>();
        final Queue<Runnable> ioQueue = new ArrayDeque<>();
        final Map<Player, Set<String>> perms = new HashMap<>();
        BooleanSupplier tick;
        StaffSpyService spyService;

        FoliaHarness() throws Exception {
            config.set("modules.chat", true);
            config.set("chat.channels.enabled", true);
            config.set("chat.mentions.enabled", true);
            config.set("chat.mentions.color", "&e");
            config.set("chat.mentions.sound", "minecraft:entity.experience_orb.pickup");
            config.set("chat.deletion_resend.clear_lines", 10);
            config.set("chat.replay-packets-per-tick", 64);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getScheduler()).thenReturn(scheduler);
            when(plugin.getMessageManager()).thenReturn(messages);
            when(plugin.getPlayerNames()).thenReturn(names);
            when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
            when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));
            when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            when(names.resolve(anyString())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
            when(messages.resolve(any(Messages.class), any(String[].class))).thenAnswer(inv -> {
                Messages m = inv.getArgument(0);
                String out = m.getDefault();
                Object[] raw = inv.getArguments();
                for (int i = 1; i + 1 < raw.length; i += 2) {
                    out = out.replace(String.valueOf(raw[i]), String.valueOf(raw[i + 1]));
                }
                return out;
            });

            when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(inv -> {
                globalQueue.add(inv.getArgument(0));
                return TaskHandle.NONE;
            });
            when(scheduler.repeatOnServer(any(BooleanSupplier.class), anyLong(), anyLong())).thenAnswer(inv -> {
                tick = inv.getArgument(0);
                return TaskHandle.NONE;
            });
            when(scheduler.runOn(any(ServerThread.class), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(inv -> {
                ServerThread region = inv.getArgument(0);
                Runnable task = inv.getArgument(1);
                Runnable retired = inv.getArgument(2);
                regionQueues.computeIfAbsent(region, ignored -> new ArrayDeque<>()).add(new Scheduled(task, retired));
                return TaskHandle.NONE;
            });

            spyService = new StaffSpyService(plugin);
            ModerationModule modModule = mock(ModerationModule.class);
            ModerationService modSvc = mock(ModerationService.class);
            when(modModule.isEnabled()).thenReturn(true);
            when(modModule.moderation()).thenReturn(modSvc);
            when(modModule.spy()).thenReturn(spyService);
            when(modSvc.isMuteStateReady(any())).thenReturn(true);
            when(modSvc.isMuted(any())).thenReturn(false);
            when(modSvc.isVanished(any(Player.class))).thenReturn(false);
            ModuleManager modules = mock(ModuleManager.class);
            when(modules.getModule("moderation")).thenReturn(modModule);
            when(plugin.getModuleManager()).thenReturn(modules);

            when(repository.load(any())).thenAnswer(inv ->
                    new ChatRepository.State(inv.getArgument(0), null, "default", Set.of("default"), 1));
        }

        Player player(String name, World w, double x, double z) {
            Player p = mock(Player.class);
            UUID id = UUID.randomUUID();
            ServerThread region = mock(ServerThread.class, name + "-region");
            when(region.isServerThread()).thenAnswer(inv -> currentRegion.get() == region);
            playerRegions.put(p, region);
            regionQueues.put(region, new ArrayDeque<>());

            when(p.getName()).thenReturn(name);
            when(p.getUniqueId()).thenReturn(id);
            when(p.isOnline()).thenReturn(true);
            when(p.getDisplayName()).thenReturn(name);
            when(p.canSee(any(Player.class))).thenReturn(true);
            when(p.getWorld()).thenAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".getWorld() called off owning region");
                return w;
            });
            when(p.getLocation()).thenAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".getLocation() called off owning region");
                return new Location(w, x, 64, z);
            });
            doAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".setDisplayName() called off owning region");
                return null;
            }).when(p).setDisplayName(any());
            doAnswer(inv -> {
                assertEquals(region, currentRegion.get(), name + ".setPlayerListName() called off owning region");
                return null;
            }).when(p).setPlayerListName(any());

            Player.Spigot spigot = mock(Player.Spigot.class);
            when(p.spigot()).thenReturn(spigot);

            Set<String> set = new HashSet<>(Set.of(
                    "allium.channel.join.default.read", "allium.channel.join.default.write",
                    "allium.channel.join.local.read", "allium.channel.join.local.write",
                    ChatService.RECEIVE_LOCAL, ChatService.RECEIVE_SHOUT,
                    ChatService.RECEIVE_QUESTION, ChatService.RECEIVE_MENTION));
            perms.put(p, set);
            when(p.hasPermission(anyString())).thenAnswer(inv -> set.contains(inv.getArgument(0)));
            return p;
        }

        ServerThread regionOf(Player p) {
            return playerRegions.get(p);
        }

        int drainRegion(Player p) {
            ServerThread region = regionOf(p);
            Queue<Scheduled> queue = regionQueues.get(region);
            int count = 0;
            ServerThread prev = currentRegion.getAndSet(region);
            try {
                while (!queue.isEmpty()) {
                    queue.poll().run().run();
                    count++;
                }
            } finally {
                currentRegion.set(prev);
            }
            return count;
        }

        void retireNext(Player p) {
            Scheduled next = regionQueues.get(regionOf(p)).poll();
            if (next != null) {
                next.retired().run();
            }
        }

        void drainIo() {
            while (!ioQueue.isEmpty()) {
                ioQueue.poll().run();
            }
        }

        void runInRegion(Player p, Runnable body) {
            ServerThread prev = currentRegion.getAndSet(regionOf(p));
            try {
                body.run();
            } finally {
                currentRegion.set(prev);
            }
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
    void periodicRefreshRoutesToEntityRegionAndCoalescesInFlight() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player alice = h.player("Alice", h.world, 0, 0);
        doAnswer(inv -> List.of(alice)).when(h.server).getOnlinePlayers();

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            ChatService service = new ChatService(h.plugin, h.repository, h.ioQueue::add, mock(ChatRelay.class));
            service.enable();
            h.drainIo();
            assertEquals(1, h.drainRegion(alice));
            assertTrue(service.ready(alice.getUniqueId()));

            // Global tick should queue one entity-region refresh and coalesce a second tick while in-flight.
            h.currentRegion.set(null);
            h.tick.getAsBoolean();
            h.tick.getAsBoolean();
            assertEquals(1, h.regionQueues.get(h.regionOf(alice)).size(),
                    "in-flight refresh must be coalesced across ticks");

            assertEquals(1, h.drainRegion(alice));

            // Subsequent tick after completion schedules again.
            h.currentRegion.set(null);
            h.tick.getAsBoolean();
            assertEquals(1, h.regionQueues.get(h.regionOf(alice)).size());

            service.close();
        }
    }

    @Test
    void legacyChatRoutesToSenderRegionAndMentionSoundToMentionedRegion() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player alice = h.player("Alice", h.world, 0, 0);
        Player bob = h.player("Bob", h.world, 500, 0);
        h.perms.get(alice).add(ChatService.PERM_MENTION);
        doAnswer(inv -> List.of(alice, bob)).when(h.server).getOnlinePlayers();

        AtomicBoolean soundOnBobRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(bob), h.currentRegion.get(), "mention sound must run on mentioned player's region");
            soundOnBobRegion.set(true);
            return null;
        }).when(h.messages).playMessageSound(eq(bob), any(MessageSoundSpec.class));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            ChatService service = new ChatService(h.plugin, h.repository, h.ioQueue::add, mock(ChatRelay.class));
            service.enable();
            h.drainIo();
            h.drainRegion(alice);
            h.drainRegion(bob);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, alice, "hello @Bob!", new HashSet<>(Set.of(alice, bob)));
            service.legacyChat(event);

            assertTrue(event.isCancelled());
            assertTrue(h.globalQueue.isEmpty(), "legacyChat must dispatch to sender entity region, not global scheduler");
            assertEquals(1, h.regionQueues.get(h.regionOf(alice)).size());

            h.drainRegion(alice);
            assertEquals(1, service.history().visible(bob.getUniqueId(), System.currentTimeMillis()).size());
            assertFalse(soundOnBobRegion.get(), "mention sound should wait for Bob's entity region");

            h.drainRegion(bob);
            assertTrue(soundOnBobRegion.get(), "mention sound should play on Bob's entity region");

            service.close();
        }
    }

    @Test
    void localChatEvaluatesCrossRegionDistancePerRecipientAndHandlesRetiredCandidates() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player alice = h.player("Alice", h.world, 0, 0);
        Player nearBob = h.player("Bob", h.world, 20, 0);
        Player farCarol = h.player("Carol", h.world, 500, 0);
        Player retiredDan = h.player("Dan", h.world, 10, 0);
        Player staffSpy = h.player("StaffSpy", h.world, 800, 0);
        h.perms.get(staffSpy).add(StaffSpyService.PERM_CHAT_SPY);
        doAnswer(inv -> List.of(alice, nearBob, farCarol, retiredDan, staffSpy)).when(h.server).getOnlinePlayers();

        AtomicBoolean spyDeliveredOnOwnRegion = new AtomicBoolean(false);
        Player.Spigot spySpigot = staffSpy.spigot();
        doAnswer(inv -> {
            assertEquals(h.regionOf(staffSpy), h.currentRegion.get(), "local spy message must run on spy's region");
            spyDeliveredOnOwnRegion.set(true);
            return null;
        }).when(spySpigot).sendMessage(any(BaseComponent[].class));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            ChatService service = new ChatService(h.plugin, h.repository, h.ioQueue::add, mock(ChatRelay.class));
            service.enable();
            h.drainIo();
            h.drainRegion(alice);
            h.drainRegion(nearBob);
            h.drainRegion(farCarol);
            h.drainRegion(retiredDan);
            h.drainRegion(staffSpy);

            // Alice sends local chat on Alice's region. Other players' getLocation() is asserted to ONLY run on their own region!
            h.runInRegion(alice, () -> service.send(alice, "local", "hello nearby", null));

            assertTrue(service.history().visible(alice.getUniqueId(), System.currentTimeMillis()).isEmpty(),
                    "local chat waits until cross-region distance checks complete");

            h.drainRegion(nearBob);
            h.drainRegion(farCarol);
            h.retireNext(retiredDan); // Dan disconnects before his region tick runs.
            h.drainRegion(staffSpy);

            // All candidates settled -> finishSend scheduled back onto Alice's region.
            h.drainRegion(alice);

            assertEquals(1, service.history().visible(alice.getUniqueId(), System.currentTimeMillis()).size());
            assertEquals(1, service.history().visible(nearBob.getUniqueId(), System.currentTimeMillis()).size());
            assertTrue(service.history().visible(farCarol.getUniqueId(), System.currentTimeMillis()).isEmpty());
            assertTrue(service.history().visible(retiredDan.getUniqueId(), System.currentTimeMillis()).isEmpty());

            // Local spy message queued for StaffSpy's region.
            assertFalse(spyDeliveredOnOwnRegion.get());
            h.drainRegion(staffSpy);
            assertTrue(spyDeliveredOnOwnRegion.get());

            service.close();
        }
    }

    @Test
    void crossRegionNickUpdatesTargetOnTargetRegionAndRepliesOnAdminRegion() throws Exception {
        FoliaHarness h = new FoliaHarness();
        Player admin = h.player("Admin", h.world, 0, 0);
        Player bob = h.player("Bob", h.world, 400, 0);
        h.perms.get(admin).add("allium.nick.others");
        h.perms.get(admin).add("allium.nick.impersonate");
        doAnswer(inv -> List.of(admin, bob)).when(h.server).getOnlinePlayers();
        when(h.server.getPlayerExact("Bob")).thenReturn(bob);
        UUID bobId = bob.getUniqueId();
        var resolvedBob = CompletableFuture.completedFuture(
                Optional.of(new NameCache.Entry(bobId, "Bob", System.currentTimeMillis())));
        when(h.names.resolve("Bob")).thenReturn(resolvedBob);
        var savedState = new ChatRepository.State(bobId, "Crimson", "default", Set.of("default"), 2);

        AtomicBoolean adminRepliedOnOwnRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(admin), h.currentRegion.get(), "admin reply must run on admin's region");
            adminRepliedOnOwnRegion.set(true);
            return null;
        }).when(h.messages).send(eq(admin), eq(Messages.NICK_SET), eq("{nick}"), eq("Crimson"));

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            ChatService service = new ChatService(h.plugin, h.repository, h.ioQueue::add, mock(ChatRelay.class));
            service.enable();
            h.drainIo();
            h.drainRegion(admin);
            h.drainRegion(bob);

            when(h.repository.load(bob.getUniqueId())).thenReturn(savedState);

            var context = mock(BukkitCommandContext.class);
            when(context.sender()).thenReturn(admin);
            when(context.label()).thenReturn("nick");
            when(context.args()).thenReturn(new String[]{"Bob", "Crimson"});
            when(context.spec()).thenReturn(CoreCommandSpecs.CHAT_SPECS.stream()
                    .filter(s -> s.canonicalName().equals("nick")).findFirst().orElseThrow());

            h.runInRegion(admin, () -> service.dispatch(context));
            // Resolve future callback scheduled onto admin's region -> persists on IO -> callback on admin's region.
            h.drainRegion(admin);
            h.drainIo();
            h.drainRegion(admin);

            assertTrue(adminRepliedOnOwnRegion.get());
            verify(bob, never()).setDisplayName("Crimson"); // Not mutated off Bob's region!

            h.drainRegion(bob);
            verify(bob, times(1)).setDisplayName("Crimson");
            assertEquals("Crimson", service.placeholder(bob, false));

            service.close();
        }
    }

    @Test
    void joinQuitWorldBroadcastRoutesPerViewerToViewerRegion() throws Exception {
        FoliaHarness h = new FoliaHarness();
        h.config.set("join-and-quit-messages.broadcast-scope", "world");
        h.config.set("join-and-quit-messages.join.enabled", true);
        h.config.set("join-and-quit-messages.join.format", "&e%name% joined");

        Player joining = h.player("Alice", h.world, 0, 0);
        Player sameWorld = h.player("Bob", h.world, 300, 0);
        Player otherWorldViewer = h.player("Carol", h.otherWorld, 0, 0);
        doAnswer(inv -> List.of(joining, sameWorld, otherWorldViewer)).when(h.server).getOnlinePlayers();

        AtomicBoolean bobSentOnOwnRegion = new AtomicBoolean(false);
        doAnswer(inv -> {
            assertEquals(h.regionOf(sameWorld), h.currentRegion.get(), "join broadcast must run on viewer's region");
            bobSentOnOwnRegion.set(true);
            return null;
        }).when(sameWorld).sendMessage(anyString());

        try (MockedStatic<BukkitServerThread> ignored = h.mockFolia()) {
            JoinQuitMessages listener = new JoinQuitMessages(h.plugin);
            PlayerJoinEvent event = new PlayerJoinEvent(joining, "Alice joined the game");
            h.runInRegion(joining, () -> listener.onJoin(event));

            assertEquals(null, event.getJoinMessage(), "world-scoped join clears default server broadcast");
            assertFalse(bobSentOnOwnRegion.get(), "delivery to cross-region viewer waits for viewer's region");

            h.drainRegion(sameWorld);
            h.drainRegion(otherWorldViewer);

            assertTrue(bobSentOnOwnRegion.get(), "same-world viewer receives world-scoped join on own region");
            verify(otherWorldViewer, never()).sendMessage(anyString());
        }
    }
}
