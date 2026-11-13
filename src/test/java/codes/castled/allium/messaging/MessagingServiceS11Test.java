package codes.castled.allium.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.text.FormattedText;
import codes.castled.allium.text.TextSender;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessagingServiceS11Test {
    private PluginStart plugin;
    private ModuleManager modules;
    private PreferencesModule preferences;
    private IgnoreService ignores;
    private YamlConfiguration config;
    private MessagingService service;
    private TestPlayer alice;
    private TestPlayer bob;
    private TestPlayer spy;
    private ConsoleCommandSender console;
    private final List<Player> online = new ArrayList<>();
    private final Map<UUID, EffectivePreferences> effective = new HashMap<>();
    private final List<Delivery> deliveries = new ArrayList<>();
    private MockedStatic<TextSender> textSender;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        modules = mock(ModuleManager.class);
        preferences = mock(PreferencesModule.class);
        ignores = mock(IgnoreService.class);
        config = new YamlConfiguration();
        LanguageManager language = mock(LanguageManager.class);
        when(language.get(any(Messages.class))).thenAnswer(call -> ((Messages) call.getArgument(0)).getDefault());
        when(plugin.getMessageManager()).thenReturn(new MessageManager(plugin, language));
        when(plugin.getLanguageManager()).thenReturn(language);
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getConfig()).thenReturn(config);
        when(modules.getModule("preferences")).thenReturn(preferences);
        when(preferences.isEnabled()).thenReturn(true);
        when(preferences.acceptance(any(UUID.class), eq(PreferenceKey.ACCEPT_MESSAGES)))
                .thenReturn(PreferenceSessions.Acceptance.ACCEPTS);
        when(preferences.effective(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(effective.get(call.getArgument(0))));
        when(ignores.ignores(any(UUID.class), any(UUID.class))).thenReturn(MessagePolicy.Ignore.NO);

        console = (ConsoleCommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "getName", "toString" -> "Console";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("Unexpected console call: " + method);
                });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOnlinePlayers" -> online;
                    case "getConsoleSender" -> console;
                    default -> throw new AssertionError("Unexpected server call: " + method);
                });
        when(plugin.getServer()).thenReturn(server);

        alice = player("Alice");
        bob = player("Bob");
        spy = player("Watcher");
        enableSpy(spy);
        service = new MessagingService(plugin, ignores);
        textSender = mockStatic(TextSender.class);
        textSender.when(() -> TextSender.sendLiteral(any(CommandSender.class), any(FormattedText.class)))
                .thenAnswer(call -> {
                    deliveries.add(new Delivery(call.getArgument(0), call.getArgument(1), true));
                    return null;
                });
        textSender.when(() -> TextSender.send(any(CommandSender.class), any(FormattedText.class)))
                .thenAnswer(call -> {
                    deliveries.add(new Delivery(call.getArgument(0), call.getArgument(1), false));
                    return null;
                });
    }

    @AfterEach
    void closeTextCapture() {
        if (textSender != null) {
            textSender.close();
        }
    }

    @Test
    void exposesAReusableNotificationServiceAndCurrentMuteSource() {
        NotificationService notifications = service.notifications();
        assertNotNull(notifications);
        assertSame(notifications, service.notifications());
        notifications.send(alice.player, new NotificationService.Notification(Messages.SOCIALSPY_ON,
                Map.of(), null, NotificationService.Sound.NONE));
        assertEquals(1, texts(alice.player).size());
        assertTrue(texts(alice.player).get(0).contains("Social spy enabled"));

        assertFalse(service.muted(alice.uuid));
        Set<UUID> muted = new HashSet<>();
        service.setMuteSource(muted::contains);
        muted.add(alice.uuid);
        assertTrue(service.muted(alice.uuid));
        assertFalse(service.muted(bob.uuid));
        muted.clear();
        assertFalse(service.muted(alice.uuid), "the accessor must query the current source");
        muted.add(alice.uuid);
        service.setMuteSource(null);
        assertFalse(service.muted(alice.uuid), "null restores the unmuted default");
    }

    @Test
    void deliverySendsOneLiteralCopyToEachParticipantAndEligibleObserver() {
        String body = "&c{sender} <click:run_command:/op Mallory>x</click> https://example.com";

        assertEquals(MessagePolicy.Outcome.DELIVER, send(body));

        assertEquals(List.of("[me → Bob] " + body), texts(alice.player));
        assertEquals(List.of("[Alice → me] " + body), texts(bob.player));
        assertEquals(List.of("[Spy] [Alice → Bob] " + body), texts(spy.player));
        assertTrue(texts(console).isEmpty(), "logging is off when its config key is absent");
        assertEquals(3, deliveries.size());
        assertTrue(deliveries.stream().allMatch(Delivery::literal));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-opted-in", "not-loaded", "no-permission", "sender-exempt", "recipient-exempt",
            "preferences-disabled", "preferences-absent"})
    void observerEligibilityFailsClosedWithoutChangingPrivateDelivery(String gate) {
        switch (gate) {
            case "not-opted-in" -> effective.put(spy.uuid, preference(false));
            case "not-loaded" -> effective.remove(spy.uuid);
            case "no-permission" -> spy.permissions.remove("allium.socialspy");
            case "sender-exempt" -> alice.permissions.add("allium.socialspy.exempt");
            case "recipient-exempt" -> bob.permissions.add("allium.socialspy.exempt");
            case "preferences-disabled" -> when(preferences.isEnabled()).thenReturn(false);
            case "preferences-absent" -> when(modules.getModule("preferences")).thenReturn(null);
            default -> fail(gate);
        }

        assertEquals(MessagePolicy.Outcome.DELIVER, send("private"));

        assertEquals(List.of("[me → Bob] private"), texts(alice.player));
        assertEquals(List.of("[Alice → me] private"), texts(bob.player));
        assertTrue(texts(spy.player).isEmpty(), gate);
        assertEquals(2, deliveries.size());
    }

    @Test
    void enabledParticipantsAndOtherObjectsWithTheirUuidsNeverReceiveAnObserverCopy() {
        enableSpy(alice);
        enableSpy(bob);
        TestPlayer sameAlice = player("AliceConnection", alice.uuid);
        TestPlayer sameBob = player("BobConnection", bob.uuid);
        sameAlice.permissions.add("allium.socialspy");
        sameBob.permissions.add("allium.socialspy");

        assertEquals(MessagePolicy.Outcome.DELIVER, send("private"));

        assertEquals(1, texts(alice.player).size());
        assertEquals(1, texts(bob.player).size());
        assertEquals(List.of("[Spy] [Alice → Bob] private"), texts(spy.player));
        assertTrue(texts(sameAlice.player).isEmpty());
        assertTrue(texts(sameBob.player).isEmpty());
        assertEquals(3, deliveries.size(), "participant exclusion is based on UUID");
    }

    @ParameterizedTest
    @CsvSource({"sender, hide-player", "recipient, hide-player", "sender, vanish", "recipient, vanish"})
    void observerMustSeeBothParticipants(String participant, String hiding) {
        TestPlayer hidden = participant.equals("sender") ? alice : bob;
        hideFromSpy(hidden, hiding);

        assertEquals(MessagePolicy.Outcome.DELIVER, send("private"));

        assertFalse(service.visibleTo(spy.player, hidden.player));
        assertTrue(texts(spy.player).isEmpty(), participant + " hidden by " + hiding);
        assertEquals(2, deliveries.size());
    }

    @ParameterizedTest
    @CsvSource({"sender, hide-player", "recipient, hide-player", "sender, vanish", "recipient, vanish"})
    void seeVanishedPermissionUsesVisibleToSemanticsForObservers(String participant, String hiding) {
        TestPlayer hidden = participant.equals("sender") ? alice : bob;
        hideFromSpy(hidden, hiding);
        spy.permissions.add(MessagePolicy.SEE_VANISHED);

        assertEquals(MessagePolicy.Outcome.DELIVER, send("private"));

        assertTrue(service.visibleTo(spy.player, hidden.player));
        assertEquals(List.of("[Spy] [Alice → Bob] private"), texts(spy.player));
    }

    @ParameterizedTest
    @EnumSource(value = MessagePolicy.Outcome.class, names = "DELIVER", mode = EnumSource.Mode.EXCLUDE)
    void refusedAttemptsNeverReachObserversOrConsole(MessagePolicy.Outcome expected) {
        config.set("messaging.socialspy.log-to-console", true);
        CommandSender recipient = bob.player;
        switch (expected) {
            case SENDER_MUTED -> service.setMuteSource(alice.uuid::equals);
            case NOT_FOUND -> recipient = null;
            case SELF -> recipient = alice.player;
            case CANNOT_RECEIVE -> when(ignores.ignores(bob.uuid, alice.uuid)).thenReturn(MessagePolicy.Ignore.YES);
            case UNAVAILABLE -> when(preferences.acceptance(bob.uuid, PreferenceKey.ACCEPT_MESSAGES))
                    .thenReturn(PreferenceSessions.Acceptance.UNAVAILABLE);
            default -> fail("unexpected outcome " + expected);
        }

        assertEquals(expected, service.send(alice.player, recipient, "Bob", "secret"));

        assertTrue(texts(spy.player).isEmpty());
        assertTrue(texts(console).isEmpty());
        assertTrue(texts(bob.player).isEmpty());
        assertEquals(1, texts(alice.player).size(), "the refusal is visible to the sender only");
        assertFalse(texts(alice.player).get(0).contains("secret"));
        assertTrue(deliveries.stream().noneMatch(Delivery::literal), "no notification delivery for a refused attempt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hidden", "offline", "ignores-loading", "messages-off"})
    void otherPrivacyRefusalsNeverLeakTheBody(String reason) {
        config.set("messaging.socialspy.log-to-console", true);
        MessagePolicy.Outcome expected;
        switch (reason) {
            case "hidden" -> {
                alice.hidden.add(bob.uuid);
                expected = MessagePolicy.Outcome.NOT_FOUND;
            }
            case "offline" -> {
                bob.isOnline = false;
                expected = MessagePolicy.Outcome.NOT_FOUND;
            }
            case "ignores-loading" -> {
                when(ignores.ignores(bob.uuid, alice.uuid)).thenReturn(MessagePolicy.Ignore.UNKNOWN);
                expected = MessagePolicy.Outcome.UNAVAILABLE;
            }
            case "messages-off" -> {
                when(preferences.acceptance(bob.uuid, PreferenceKey.ACCEPT_MESSAGES))
                        .thenReturn(PreferenceSessions.Acceptance.REFUSES);
                expected = MessagePolicy.Outcome.CANNOT_RECEIVE;
            }
            default -> throw new AssertionError(reason);
        }

        assertEquals(expected, send("secret"));

        assertEquals(1, deliveries.size());
        assertSame(alice.player, deliveries.get(0).recipient());
        assertFalse(deliveries.get(0).body().toPlainText().contains("secret"));
    }

    @Test
    void observerEligibilityIsRecheckedOnEveryDelivery() {
        assertEquals(MessagePolicy.Outcome.DELIVER, send("one"));
        spy.permissions.remove("allium.socialspy");
        assertEquals(MessagePolicy.Outcome.DELIVER, send("two"));
        spy.permissions.add("allium.socialspy");
        effective.put(spy.uuid, preference(false));
        assertEquals(MessagePolicy.Outcome.DELIVER, send("three"));
        effective.put(spy.uuid, preference(true));
        assertEquals(MessagePolicy.Outcome.DELIVER, send("four"));

        assertEquals(List.of("[Spy] [Alice → Bob] one", "[Spy] [Alice → Bob] four"), texts(spy.player));
    }

    @Test
    void explicitConsoleLoggingSeesVanishedParticipantsEvenWhenObserversCannot() {
        config.set("messaging.socialspy.log-to-console", true);
        service.setVanishSource(player -> player.getUniqueId().equals(alice.uuid));

        assertEquals(MessagePolicy.Outcome.DELIVER, send("private"));

        assertTrue(texts(spy.player).isEmpty());
        assertEquals(List.of("[Spy] [Alice → Bob] private"), texts(console));
        assertTrue(service.visibleTo(console, alice.player));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exemptionsAlsoPreventExplicitConsoleLogging(boolean senderExempt) {
        config.set("messaging.socialspy.log-to-console", true);
        (senderExempt ? alice : bob).permissions.add("allium.socialspy.exempt");

        assertEquals(MessagePolicy.Outcome.DELIVER, send("private"));

        assertTrue(texts(console).isEmpty());
        assertTrue(texts(spy.player).isEmpty());
        assertEquals(2, deliveries.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void consoleConversationsAreObservedWithoutAnExtraConsoleLog(boolean consoleSends) {
        config.set("messaging.socialspy.log-to-console", true);
        CommandSender sender = consoleSends ? console : alice.player;
        CommandSender recipient = consoleSends ? alice.player : console;

        assertEquals(MessagePolicy.Outcome.DELIVER, service.send(sender, recipient, recipient.getName(), "private"));

        assertEquals(1, texts(console).size(), "console already receives its participant copy");
        assertEquals(1, texts(alice.player).size());
        assertEquals(List.of(consoleSends ? "[Spy] [Console → Alice] private" : "[Spy] [Alice → Console] private"),
                texts(spy.player), "console's blanket permissions must not count as a player exemption");
        assertEquals(3, deliveries.size());
    }

    @Test
    void consoleLoggingIsIndependentOfThePreferencesModule() {
        config.set("messaging.socialspy.log-to-console", true);
        when(modules.getModule("preferences")).thenReturn(null);

        assertEquals(MessagePolicy.Outcome.DELIVER, send("private"));

        assertTrue(texts(spy.player).isEmpty());
        assertEquals(List.of("[Spy] [Alice → Bob] private"), texts(console));
    }

    @Test
    void repliesObserveOnlyDeliveredBodiesAndRecheckMuteAndVisibility() {
        config.set("messaging.socialspy.log-to-console", true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(alice.uuid)).thenReturn(alice.player);
            bukkit.when(() -> Bukkit.getPlayer(bob.uuid)).thenReturn(bob.player);
            assertEquals(MessagePolicy.Outcome.DELIVER, send("first"));
            deliveries.clear();

            service.reply(bob.player, "reply");
            assertEquals(List.of("[Spy] [Bob → Alice] reply"), texts(spy.player));
            assertEquals(List.of("[Spy] [Bob → Alice] reply"), texts(console));
            assertEquals(List.of("[Bob → me] reply"), texts(alice.player), "reply goes to the real partner");
            deliveries.clear();

            service.setMuteSource(bob.uuid::equals);
            service.reply(bob.player, "muted secret");
            assertTrue(texts(spy.player).isEmpty());
            assertTrue(texts(console).isEmpty());
            assertTrue(texts(alice.player).isEmpty());
            deliveries.clear();

            service.setMuteSource(null);
            spy.hidden.add(alice.uuid);
            service.reply(bob.player, "hidden reply");
            assertTrue(texts(spy.player).isEmpty(), "an earlier observation does not authorize later replies");
            assertEquals(List.of("[Spy] [Bob → Alice] hidden reply"), texts(console));
            assertEquals(List.of("[Bob → me] hidden reply"), texts(alice.player));
        }
    }

    @Test
    void observerNotificationDoesNotCreateAReplyTargetForTheObserver() {
        assertEquals(MessagePolicy.Outcome.DELIVER, send("first"));
        deliveries.clear();

        service.reply(spy.player, "must not be sent");

        assertTrue(texts(alice.player).isEmpty());
        assertTrue(texts(bob.player).isEmpty());
        assertEquals(1, texts(spy.player).size());
        assertTrue(texts(spy.player).get(0).contains("nobody to reply to"));
    }

    private MessagePolicy.Outcome send(String body) {
        return service.send(alice.player, bob.player, "Bob", body);
    }

    private List<String> texts(CommandSender recipient) {
        return deliveries.stream().filter(delivery -> delivery.recipient() == recipient)
                .map(delivery -> delivery.body().toPlainText()).toList();
    }

    private void hideFromSpy(TestPlayer hidden, String hiding) {
        if (hiding.equals("hide-player")) {
            spy.hidden.add(hidden.uuid);
        } else {
            service.setVanishSource(player -> player.getUniqueId().equals(hidden.uuid));
            alice.permissions.add(MessagePolicy.SEE_VANISHED);
        }
    }

    private TestPlayer player(String name) {
        return player(name, UUID.randomUUID());
    }

    private TestPlayer player(String name, UUID uuid) {
        TestPlayer player = new TestPlayer(name, uuid);
        online.add(player.player);
        effective.putIfAbsent(uuid, preference(false));
        return player;
    }

    private void enableSpy(TestPlayer player) {
        player.permissions.add("allium.socialspy");
        effective.put(player.uuid, preference(true));
    }

    private static EffectivePreferences preference(boolean socialSpy) {
        return new EffectivePreferences(true, true, true, 0.5D, false, socialSpy);
    }

    private record Delivery(CommandSender recipient, FormattedText body, boolean literal) { }

    /** Avoids initializing live Bukkit registries through Mockito's Player method inspection. */
    private static final class TestPlayer {
        final UUID uuid;
        final Set<String> permissions = new HashSet<>();
        final Set<UUID> hidden = new HashSet<>();
        final Player player;
        boolean isOnline = true;

        TestPlayer(String name, UUID uuid) {
            this.uuid = uuid;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getName", "toString" -> name;
                        case "hasPermission" -> permissions.contains(args[0]);
                        case "canSee" -> !hidden.contains(((Player) args[0]).getUniqueId());
                        case "getMetadata" -> List.of();
                        case "isOnline" -> isOnline;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("Unexpected player call: " + method);
                    });
        }
    }
}
