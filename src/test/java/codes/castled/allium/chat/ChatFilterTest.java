package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.moderation.ModerationRepository;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatFilterTest {

    @Test
    void phraseFilterHandlesTokensLeetspeakSplitLettersPhrasesSubstringsAndExceptions() {
        AtomicLong now = new AtomicLong(10_000L);
        ChatFilterPolicy policy = new ChatFilterPolicy(now::get);
        UUID player = UUID.randomUUID();
        ChatFilterSettings settings = new ChatFilterSettings(
                true, ChatFilterSettings.Mode.BLOCK, true, true, true,
                new ChatFilterSettings.PhraseSettings(
                        true, true, true,
                        List.of("badword", "shit", "spam"),
                        List.of("free nitro"),
                        List.of("cheatclient"),
                        List.of("badword exception")
                ),
                new ChatFilterSettings.RepeatSettings(false, 15, 2, 0.85d, true, List.of()),
                new ChatFilterSettings.RateLimitSettings(false, 0L, 0, 10)
        );

        // Normal message and trailing punctuation must not false-positive.
        assertFalse(policy.evaluate(player, "hello!", settings).blocked());
        assertFalse(policy.evaluate(player, "mybadwordish is fine as token", settings).blocked());

        // Exact token, leetspeak, color-code evasion, and split-letters are blocked.
        assertEquals(ChatFilterPolicy.Kind.PHRASE, policy.evaluate(player, "that is &cb4dw0rd!", settings).kind());
        assertEquals(ChatFilterPolicy.Kind.PHRASE, policy.evaluate(player, "oh sh!t", settings).kind());
        assertEquals(ChatFilterPolicy.Kind.PHRASE, policy.evaluate(player, "stop $p@m", settings).kind());
        assertEquals(ChatFilterPolicy.Kind.PHRASE, policy.evaluate(player, "b a d w o r d", settings).kind());

        // Multi-word phrase and substring matches.
        assertEquals(ChatFilterPolicy.Kind.PHRASE, policy.evaluate(player, "claim fr33 n1tr0 now", settings).kind());
        assertEquals(ChatFilterPolicy.Kind.PHRASE, policy.evaluate(player, "download mycheatclientv2", settings).kind());

        // Explicit exception suppresses phrase hits.
        assertFalse(policy.evaluate(player, "this is a badword exception case", settings).blocked());
    }

    @Test
    void repeatFilterBlocksSimilarMessagesHonorsWhitelistAndExpiresWindow() {
        AtomicLong now = new AtomicLong(10_000L);
        ChatFilterPolicy policy = new ChatFilterPolicy(now::get);
        UUID player = UUID.randomUUID();
        ChatFilterSettings settings = new ChatFilterSettings(
                true, ChatFilterSettings.Mode.BLOCK, true, true, true,
                ChatFilterSettings.PhraseSettings.defaults(),
                new ChatFilterSettings.RepeatSettings(true, 15, 2, 0.85d, true, List.of("gg", "wb")),
                new ChatFilterSettings.RateLimitSettings(false, 0L, 0, 10)
        );

        assertFalse(policy.evaluate(player, "selling diamonds at warp shop", settings).blocked());
        assertFalse(policy.evaluate(player, "selling diamondssss at warp shop 123", settings).blocked());
        var third = policy.evaluate(player, "selling diamonds at warp shop 456", settings);
        assertTrue(third.blocked());
        assertEquals(ChatFilterPolicy.Kind.REPEAT, third.kind());

        // Whitelisted phrases may repeat freely.
        for (int i = 0; i < 5; i++) {
            assertFalse(policy.evaluate(player, "gg", settings).blocked());
        }

        // After the window expires, the earlier phrase is allowed again.
        now.addAndGet(16_000L);
        assertFalse(policy.evaluate(player, "selling diamonds at warp shop", settings).blocked());
    }

    @Test
    void rateLimitEnforcesMinIntervalAndBurstWindow() {
        AtomicLong now = new AtomicLong(100_000L);
        ChatFilterPolicy policy = new ChatFilterPolicy(now::get);
        UUID player = UUID.randomUUID();
        ChatFilterSettings settings = new ChatFilterSettings(
                true, ChatFilterSettings.Mode.BLOCK, true, true, true,
                ChatFilterSettings.PhraseSettings.defaults(),
                new ChatFilterSettings.RepeatSettings(false, 15, 2, 0.85d, true, List.of()),
                new ChatFilterSettings.RateLimitSettings(true, 1000L, 3, 10)
        );

        assertFalse(policy.evaluate(player, "msg one", settings).blocked());
        now.addAndGet(400L);
        var tooFast = policy.evaluate(player, "msg two", settings);
        assertTrue(tooFast.blocked());
        assertEquals(ChatFilterPolicy.Kind.RATE_LIMIT, tooFast.kind());
        assertEquals(600L, tooFast.retryAfterMillis());

        now.addAndGet(700L); // t = 101_100
        assertFalse(policy.evaluate(player, "msg two", settings).blocked());
        now.addAndGet(1100L); // t = 102_200
        assertFalse(policy.evaluate(player, "msg three", settings).blocked());
        now.addAndGet(1100L); // t = 103_300 -> 3 messages already in 10s burst window
        var burst = policy.evaluate(player, "msg four", settings);
        assertTrue(burst.blocked());
        assertEquals(ChatFilterPolicy.Kind.RATE_LIMIT, burst.kind());
        assertTrue(burst.retryAfterMillis() > 0L);

        // Advance past the 10s window of msg one (100_000 + 10_001).
        now.set(110_001L);
        assertFalse(policy.evaluate(player, "msg four", settings).blocked());
    }

    @Test
    void settingsValidateBoundsAndDefaultDisabled() {
        ChatFilterSettings defaults = ChatFilterSettings.read(new YamlConfiguration());
        assertFalse(defaults.enabled());

        YamlConfiguration badMode = new YamlConfiguration();
        badMode.set("chat.filter.enabled", true);
        badMode.set("chat.filter.mode", "kick");
        assertThrows(IllegalArgumentException.class, () -> ChatFilterSettings.read(badMode));

        YamlConfiguration badThreshold = new YamlConfiguration();
        badThreshold.set("chat.filter.repeat.similarity-threshold", 1.5d);
        assertThrows(IllegalArgumentException.class, () -> ChatFilterSettings.read(badThreshold));

        YamlConfiguration badInterval = new YamlConfiguration();
        badInterval.set("chat.filter.rate-limit.min-interval-millis", -5);
        assertThrows(IllegalArgumentException.class, () -> ChatFilterSettings.read(badInterval));
    }

    @Test
    void chatServiceBlocksRelaysAlertsStaffAndAuditsToModeration() throws Exception {
        Harness h = new Harness();
        h.config.set("chat.filter.enabled", true);
        h.config.set("chat.filter.mode", "block");
        h.config.set("chat.filter.phrases.tokens", List.of("badword"));
        h.config.set("chat.filter.rate-limit.min-interval-millis", 1000L);
        h.config.set("chat.filter.repeat.max-similar", 1);
        h.config.set("chat.channels.list.default.discord-channel-id", "1234567890");
        h.service.reload();

        // 1. Blocked phrase never reaches Bob, ChatHistory, or DiscordRelay, alerts Staff, and records audit.
        UUID aliceId = h.alice.getUniqueId();
        h.service.send(h.alice, null, "you are a b4dw0rd", null);
        h.flush();
        assertTrue(h.history(h.bob).isEmpty());
        verifyNoInteractions(h.relay);
        verify(h.messages).send(h.alice, Messages.CHAT_FILTER_PHRASE_BLOCKED);
        assertFalse(h.staffAlerts.isEmpty(), "Staff with alerts permission should receive an alert");
        assertTrue(h.bobAlerts.isEmpty(), "Non-staff should not receive filter alerts");
        verify(h.modRepo).recordAudit(eq("CHAT_FILTER"), eq("ChatFilter"),
                eq(aliceId), eq("Alice"), contains("action=BLOCK"));

        // 2. /me (/action) is also filtered.
        h.now.addAndGet(2000L);
        h.command(h.alice, "action", "me", "says", "badword");
        h.flush();
        assertTrue(h.history(h.bob).isEmpty());

        // 3. Rate limit blocks rapid follow-up messages and formats retry time.
        h.now.addAndGet(2000L);
        h.service.send(h.alice, null, "normal hello", null);
        assertEquals(1, h.history(h.bob).size());
        h.now.addAndGet(200L);
        h.service.send(h.alice, null, "too fast", null);
        assertEquals(1, h.history(h.bob).size());
        verify(h.messages).send(h.alice, Messages.CHAT_FILTER_RATE_BLOCKED, "{time}", "0.8");

        // 4. Bypass permission skips all rules.
        h.grants.get(h.alice).add(ChatService.PERM_FILTER_BYPASS);
        h.service.send(h.alice, null, "badword right away", null);
        assertEquals(2, h.history(h.bob).size());
        h.grants.get(h.alice).remove(ChatService.PERM_FILTER_BYPASS);

        // 5. Staff channel is exempt when exempt-staff-channel is true.
        h.grants.get(h.alice).add("allium.staffchat");
        h.service.send(h.alice, "staff-chat", "reviewing badword report", null);
        assertTrue(h.history(h.alice).stream().anyMatch(m -> m.channel().equals("staff-chat")));

        // 6. Alert-only mode delivers message normally while still auditing & alerting staff.
        h.config.set("chat.filter.mode", "alert-only");
        h.service.reload();
        int alertsBefore = h.staffAlerts.size();
        h.now.addAndGet(2000L);
        h.service.send(h.alice, null, "shadow badword", null);
        h.flush();
        assertEquals(3, h.history(h.bob).size());
        assertTrue(h.staffAlerts.size() > alertsBefore);
        verify(h.modRepo).recordAudit(eq("CHAT_FILTER"), eq("ChatFilter"),
                eq(aliceId), eq("Alice"), contains("action=ALERT_ONLY"));
    }

    private static final class Harness {
        final PluginStart plugin = mock(PluginStart.class);
        final MessageManager messages = mock(MessageManager.class);
        final YamlConfiguration config = new YamlConfiguration();
        final ChatRepository repo = mock(ChatRepository.class);
        final ModerationRepository modRepo = mock(ModerationRepository.class);
        final ChatRelay relay = mock(ChatRelay.class);
        final AtomicLong now = new AtomicLong(50_000L);
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        final Map<Player, Set<String>> grants = new HashMap<>();
        final List<String> staffAlerts = new ArrayList<>();
        final List<String> bobAlerts = new ArrayList<>();
        final World world = mock(World.class);
        final Player alice;
        final Player bob;
        final Player staff;
        final ChatService service;

        Harness() throws Exception {
            config.set("chat.deletion_resend.clear_lines", 100);
            config.set("chat.replay-packets-per-tick", 64);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getMessageManager()).thenReturn(messages);
            when(messages.resolve(eq(Messages.CHAT_FILTER_STAFF_ALERT), any(String[].class)))
                    .thenReturn("&8[ChatFilter] &cAlice &7(rule) &8» &f{message}");
            when(plugin.logger()).thenReturn(mock(codes.castled.allium.util.AlliumLogger.class));

            Server server = mock(Server.class);
            when(plugin.getServer()).thenReturn(server);
            when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));
            when(server.getPluginManager()).thenReturn(mock(PluginManager.class));

            PlayerNameService names = mock(PlayerNameService.class);
            when(plugin.getPlayerNames()).thenReturn(names);
            when(names.resolve(anyString())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

            TaskScheduler scheduler = mock(TaskScheduler.class);
            when(plugin.getScheduler()).thenReturn(scheduler);
            when(scheduler.runOnServer(any())).thenAnswer(call -> { callbacks.add(call.getArgument(0)); return TaskHandle.NONE; });
            when(scheduler.runOnServerLater(any(), anyLong())).thenReturn(TaskHandle.NONE);
            when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);

            alice = player("Alice", null);
            bob = player("Bob", bobAlerts);
            staff = player("Staff", staffAlerts);
            grants.get(staff).add(ChatService.PERM_FILTER_ALERTS);
            doReturn(List.of(alice, bob, staff)).when(server).getOnlinePlayers();

            ModuleManager modules = mock(ModuleManager.class);
            when(plugin.getModuleManager()).thenReturn(modules);
            ModerationModule mod = mock(ModerationModule.class);
            ModerationService modSvc = mock(ModerationService.class);
            when(modules.getModule("moderation")).thenReturn(mod);
            when(mod.isEnabled()).thenReturn(true);
            when(mod.moderation()).thenReturn(modSvc);
            when(mod.repository()).thenReturn(modRepo);
            when(modSvc.isMuteStateReady(any())).thenReturn(true);

            when(repo.load(any())).thenAnswer(call -> new ChatRepository.State(call.getArgument(0), null, "", Set.of(), 0));
            service = new ChatService(plugin, repo, jobs::add, relay, null, now::get);
            service.enable();
            flush();
        }

        private Player player(String name, List<String> literalSink) {
            Player p = mock(Player.class);
            when(p.getName()).thenReturn(name);
            when(p.getUniqueId()).thenReturn(UUID.randomUUID());
            when(p.isOnline()).thenReturn(true);
            when(p.getWorld()).thenReturn(world);
            when(p.getLocation()).thenReturn(new Location(world, 0, 64, 0));
            when(p.getDisplayName()).thenReturn(name);
            Set<String> perms = new HashSet<>(Set.of(
                    "allium.channel.join.default.read", "allium.channel.join.default.write",
                    "allium.channel.join.local.write", ChatService.RECEIVE_LOCAL, ChatService.RECEIVE_SHOUT,
                    ChatService.RECEIVE_QUESTION, ChatService.RECEIVE_MENTION));
            grants.put(p, perms);
            when(p.hasPermission(anyString())).thenAnswer(call -> perms.contains(call.getArgument(0)));
            when(p.canSee(any(Player.class))).thenReturn(true);
            Player.Spigot spigot = mock(Player.Spigot.class);
            when(p.spigot()).thenReturn(spigot);
            if (literalSink != null) {
                doAnswer(call -> {
                    var comps = (net.md_5.bungee.api.chat.BaseComponent[]) call.getRawArguments()[0];
                    literalSink.add(net.md_5.bungee.api.chat.BaseComponent.toPlainText(comps));
                    return null;
                }).when(spigot).sendMessage(any(net.md_5.bungee.api.chat.BaseComponent[].class));
            }
            return p;
        }

        void flush() {
            int budget = 500;
            while ((!jobs.isEmpty() || !callbacks.isEmpty()) && --budget > 0) {
                if (!jobs.isEmpty()) jobs.remove().run();
                if (!callbacks.isEmpty()) callbacks.remove().run();
            }
            assertTrue(budget > 0);
        }

        List<ChatHistory.Message> history(Player p) {
            return service.history().visible(p.getUniqueId(), System.currentTimeMillis());
        }

        void command(Player p, String action, String label, String... args) {
            var context = mock(BukkitCommandContext.class);
            when(context.sender()).thenReturn(p);
            when(context.label()).thenReturn(label);
            when(context.args()).thenReturn(args);
            when(context.spec()).thenReturn(CoreCommandSpecs.CHAT_SPECS.stream()
                    .filter(s -> s.canonicalName().equals(action)).findFirst().orElseThrow());
            service.dispatch(context);
        }
    }
}
