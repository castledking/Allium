package codes.castled.allium.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.LanguageManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.MessageSoundSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.NotificationService.Notification;
import codes.castled.allium.messaging.NotificationService.Sound;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationRouterS11Test {
    private static final String SOUND_ID = "minecraft:block.note_block.bell";
    private static final MessageSoundSpec SOUND = new MessageSoundSpec(SOUND_ID, 0.6D, 1.4D);

    @Test
    void resolvesTrustedPlaceholdersAndInsertsTheParsedBodyWithoutReinterpretingIt() {
        Fixture fixture = new Fixture();
        when(fixture.language.get(Messages.PREFIX)).thenReturn("&6Allium ");
        when(fixture.language.get(Messages.MSG_RECEIVED)).thenReturn(
                "{prefix}&7[&f{sender} &7→ &f{recipient}&7] &f{message}");
        String body = "&c{sender} <click:run_command:/op Mallory>hi</click> https://example.com";

        fixture.router.send(fixture.recipient.player, new Notification(Messages.MSG_RECEIVED,
                Map.of("{sender}", "Alice", "{recipient}", "Bob"),
                UntrustedText.parse(body, UntrustedText.Allow.NONE), Sound.MESSAGE));

        BaseComponent[] delivered = fixture.recipient.assertSingleText("Allium [Alice → Bob] " + body);
        assertEquals(ChatColor.GOLD, delivered[0].getColor(), "the trusted prefix is formatted");
        BaseComponent literalBody = Arrays.stream(delivered)
                .filter(component -> component.toPlainText().contains("&c{sender}"))
                .findFirst().orElseThrow();
        assertEquals(ChatColor.WHITE, literalBody.getColor(), "the body inherits the template, not its literal &c");
        assertNonInteractive(delivered);
        fixture.recipient.assertSingleSound(0.3F, 1.4F);
    }

    @Test
    void preservesBodyFormattingThatWasAlreadyGrantedByTheBodyParser() {
        Fixture fixture = new Fixture();
        fixture.router.send(fixture.recipient.player, new Notification(Messages.MSG_RECEIVED,
                Map.of("{sender}", "Alice"),
                UntrustedText.parse("&cred", new UntrustedText.Allow(true, false, false)), Sound.NONE));

        BaseComponent[] delivered = fixture.recipient.assertSingleText("From Alice: red");
        BaseComponent red = Arrays.stream(delivered)
                .filter(component -> component.toPlainText().equals("red"))
                .findFirst().orElseThrow();
        assertEquals(ChatColor.RED, red.getColor());
        assertNonInteractive(delivered);
        fixture.recipient.assertSilent();
    }

    @Test
    void bodylessNotificationFormatsItsTrustedTemplateAndDeliversOnce() {
        Fixture fixture = new Fixture();
        when(fixture.language.get(Messages.SOCIALSPY_TOGGLE)).thenReturn("&fSocialSpy {state}&f.");

        fixture.router.send(fixture.recipient.player, new Notification(Messages.SOCIALSPY_TOGGLE,
                Map.of("{state}", "&aenabled"), null, Sound.NONE));

        BaseComponent[] delivered = fixture.recipient.assertSingleText("SocialSpy enabled.");
        BaseComponent enabled = Arrays.stream(delivered)
                .filter(component -> component.toPlainText().equals("enabled"))
                .findFirst().orElseThrow();
        assertEquals(ChatColor.GREEN, enabled.getColor());
        fixture.recipient.assertSilent();
    }

    @Test
    void noneDeliversTextEvenWhenTheMessageHasASoundAndPreferencesAreAudible() {
        Fixture fixture = new Fixture();
        fixture.readyPreferences(0.75D);

        fixture.send(Sound.NONE);

        fixture.assertNormalText();
        fixture.recipient.assertSilent();
        verify(fixture.language, never()).getSound(Messages.MSG_RECEIVED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void messageUsesTheRecipientsEffectiveVolumeRegardlessOfTheSentSoundSwitch(boolean scaleSent) {
        Fixture fixture = new Fixture();
        fixture.config.set("sounds.messages.scale-sent-sound", scaleSent);
        fixture.config.set("sounds.messages.fallback-volume", 0.9D);
        PreferencesModule preferences = fixture.readyPreferences(0.25D);

        fixture.send(Sound.MESSAGE);

        fixture.assertNormalText();
        fixture.recipient.assertSingleSound(0.15F, 1.4F);
        verify(preferences).effective(fixture.recipient.uuid);
    }

    @ParameterizedTest
    @EnumSource(value = Sound.class, names = {"MESSAGE", "SENT"})
    void loadingOrFailedPreferencesAreSilentEvenWithAnAudibleConfiguredFallback(Sound sound) {
        Fixture fixture = new Fixture();
        fixture.config.set("sounds.messages.fallback-volume", 0.9D);
        PreferencesModule preferences = fixture.preferences(Optional.empty());

        fixture.send(sound);

        fixture.assertNormalText();
        fixture.recipient.assertSilent();
        verify(preferences).effective(fixture.recipient.uuid);
    }

    @ParameterizedTest
    @EnumSource(value = Sound.class, names = {"MESSAGE", "SENT"})
    void zeroEffectiveVolumeMutesOnlyTheSound(Sound sound) {
        Fixture fixture = new Fixture();
        fixture.readyPreferences(0.0D);

        fixture.send(sound);

        fixture.assertNormalText();
        fixture.recipient.assertSilent();
    }

    @ParameterizedTest
    @CsvSource({"MESSAGE, false", "MESSAGE, true", "SENT, false", "SENT, true"})
    void absentOrDisabledPreferencesUseTheConfiguredFallback(Sound sound, boolean disabledModulePresent) {
        Fixture fixture = new Fixture();
        PreferencesModule disabled = fixture.absentOrDisabledPreferences(disabledModulePresent);
        fixture.config.set("sounds.messages.fallback-volume", 0.4D);

        fixture.send(sound);

        fixture.assertNormalText();
        fixture.recipient.assertSingleSound(0.24F, 1.4F);
        if (disabled != null) {
            verify(disabled, never()).effective(any(UUID.class));
        }
    }

    @ParameterizedTest
    @CsvSource({"MESSAGE, false", "MESSAGE, true", "SENT, false", "SENT, true"})
    void zeroConfiguredFallbackIsSilentWhenPreferencesAreAbsentOrDisabled(Sound sound, boolean disabledModulePresent) {
        Fixture fixture = new Fixture();
        fixture.absentOrDisabledPreferences(disabledModulePresent);
        fixture.config.set("sounds.messages.fallback-volume", 0.0D);

        fixture.send(sound);

        fixture.assertNormalText();
        fixture.recipient.assertSilent();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sentSoundIsScaledByDefaultAndWhenExplicitlyEnabled(boolean explicitSetting) {
        Fixture fixture = new Fixture();
        if (explicitSetting) {
            fixture.config.set("sounds.messages.scale-sent-sound", true);
        }
        fixture.readyPreferences(0.25D);

        fixture.send(Sound.SENT);

        fixture.assertNormalText();
        fixture.recipient.assertSingleSound(0.15F, 1.4F);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unscaledSentSoundUsesItsOwnVolumeEvenWhenPreferencesAreUnavailableOrMuted(boolean loadedMuted) {
        Fixture fixture = new Fixture();
        fixture.config.set("sounds.messages.scale-sent-sound", false);
        fixture.config.set("sounds.messages.fallback-volume", 0.0D);
        PreferencesModule preferences = loadedMuted
                ? fixture.readyPreferences(0.0D) : fixture.preferences(Optional.empty());

        fixture.send(Sound.SENT);

        fixture.assertNormalText();
        fixture.recipient.assertSingleSound(0.6F, 1.4F);
        verify(preferences, never()).effective(any(UUID.class));
    }

    @ParameterizedTest
    @CsvSource({"MESSAGE, true", "SENT, true", "SENT, false"})
    void missingSoundStillDeliversExactlyOneText(Sound sound, boolean scaleSent) {
        Fixture fixture = new Fixture();
        fixture.config.set("sounds.messages.scale-sent-sound", scaleSent);
        when(fixture.language.getSound(Messages.MSG_RECEIVED)).thenReturn(null);

        assertDoesNotThrow(() -> fixture.send(sound));

        fixture.assertNormalText();
        fixture.recipient.assertSilent();
    }

    @ParameterizedTest
    @CsvSource({"MESSAGE, true", "SENT, true", "SENT, false"})
    void zeroAuthoredVolumeNeverPlaysASound(Sound sound, boolean scaleSent) {
        Fixture fixture = new Fixture();
        fixture.config.set("sounds.messages.scale-sent-sound", scaleSent);
        when(fixture.language.getSound(Messages.MSG_RECEIVED)).thenReturn(
                new MessageSoundSpec(SOUND_ID, 0.0D, 1.4D));
        fixture.readyPreferences(0.75D);

        fixture.send(sound);

        fixture.assertNormalText();
        fixture.recipient.assertSilent();
    }

    @ParameterizedTest
    @EnumSource(value = Sound.class, names = {"MESSAGE", "SENT"})
    void omittedSoundLevelsUseConfiguredDefaultsBeforeRecipientScaling(Sound sound) {
        Fixture fixture = new Fixture();
        when(fixture.language.getSound(Messages.MSG_RECEIVED)).thenReturn(
                new MessageSoundSpec(SOUND_ID, null, null));
        fixture.readyPreferences(0.25D);

        fixture.send(sound);

        fixture.assertNormalText();
        fixture.recipient.assertSingleSound(0.2F, 1.2F);
    }

    @ParameterizedTest
    @EnumSource(Sound.class)
    void consoleGetsOnePlainTextDeliveryAndNeverEntersPlayerSoundRouting(Sound sound) {
        Fixture fixture = new Fixture();
        fixture.config.set("sounds.messages.scale-sent-sound", false);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        fixture.router.send(console, notification(sound));

        verify(console).sendMessage("From Alice: hello");
        verifyNoMoreInteractions(console);
        verify(fixture.language, never()).getSound(Messages.MSG_RECEIVED);
        verifyNoInteractions(fixture.modules);
        fixture.recipient.assertSilent();
    }

    private static Notification notification(Sound sound) {
        return new Notification(Messages.MSG_RECEIVED, Map.of("{sender}", "Alice"),
                UntrustedText.parse("hello", UntrustedText.Allow.NONE), sound);
    }

    private static void assertNonInteractive(BaseComponent[] components) {
        for (BaseComponent component : components) {
            assertNull(component.getClickEvent(), "literal text must not acquire a click action");
            assertNull(component.getHoverEvent());
            assertNull(component.getInsertion());
            if (component.getExtra() != null) {
                assertNonInteractive(component.getExtra().toArray(BaseComponent[]::new));
            }
        }
    }

    private static final class Fixture {
        final PluginStart plugin = mock(PluginStart.class);
        final LanguageManager language = mock(LanguageManager.class);
        final ModuleManager modules = mock(ModuleManager.class);
        final YamlConfiguration config = new YamlConfiguration();
        final Recipient recipient = new Recipient();
        final NotificationService router;

        Fixture() {
            config.set("sounds.defaults.volume", 0.8D);
            config.set("sounds.defaults.pitch", 1.2D);
            config.set("sounds.messages.fallback-volume", 0.5D);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLanguageManager()).thenReturn(language);
            when(plugin.getModuleManager()).thenReturn(modules);
            when(plugin.getMessageManager()).thenReturn(new MessageManager(plugin, language));
            when(language.get(Messages.MSG_RECEIVED)).thenReturn("&7From &f{sender}&7: &f{message}");
            when(language.getSound(Messages.MSG_RECEIVED)).thenReturn(SOUND);
            router = new NotificationRouter(plugin);
        }

        PreferencesModule readyPreferences(double volume) {
            return preferences(Optional.of(new EffectivePreferences(true, true, true, volume, true)));
        }

        PreferencesModule preferences(Optional<EffectivePreferences> effective) {
            PreferencesModule preferences = mock(PreferencesModule.class);
            when(modules.getModule("preferences")).thenReturn(preferences);
            when(preferences.isEnabled()).thenReturn(true);
            when(preferences.effective(recipient.uuid)).thenReturn(effective);
            return preferences;
        }

        PreferencesModule absentOrDisabledPreferences(boolean disabledModulePresent) {
            if (!disabledModulePresent) {
                return null;
            }
            PreferencesModule disabled = mock(PreferencesModule.class);
            when(modules.getModule("preferences")).thenReturn(disabled);
            when(disabled.isEnabled()).thenReturn(false);
            return disabled;
        }

        void send(Sound sound) {
            router.send(recipient.player, notification(sound));
        }

        void assertNormalText() {
            assertNonInteractive(recipient.assertSingleText("From Alice: hello"));
        }
    }

    /** A proxy avoids Mockito inspecting Player methods that initialize Bukkit's live registries. */
    private static final class Recipient {
        final UUID uuid = UUID.fromString("a85ac490-ce9f-4c6c-a36a-32b576bf324c");
        final Location location = new Location(null, 10, 64, 20);
        final List<BaseComponent[]> texts = new ArrayList<>();
        final List<Object[]> legacyTexts = new ArrayList<>();
        final List<PlayedSound> sounds = new ArrayList<>();
        final Player.Spigot spigot = new Player.Spigot() {
            @Override
            public void sendMessage(BaseComponent... components) {
                texts.add(components);
            }
        };
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getLocation" -> location;
                    case "spigot" -> spigot;
                    case "sendMessage" -> {
                        legacyTexts.add(args.clone());
                        yield null;
                    }
                    case "playSound" -> {
                        assertEquals(4, args.length, "expected the location-based sound API");
                        sounds.add(new PlayedSound((Location) args[0], (String) args[1], (Float) args[2], (Float) args[3]));
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "NotificationRouter test recipient";
                    default -> throw new AssertionError("Unexpected player call: " + method);
                });

        BaseComponent[] assertSingleText(String expected) {
            assertEquals(1, texts.size(), "exactly one component delivery");
            assertTrue(legacyTexts.isEmpty(), "untrusted bodies must use components, preventing automatic URL links");
            BaseComponent[] delivered = texts.get(0);
            assertEquals(expected, TextComponent.toPlainText(delivered));
            return delivered;
        }

        void assertSingleSound(float volume, float pitch) {
            assertEquals(1, sounds.size(), "exactly one sound, with no duplicate implicit message sound");
            PlayedSound played = sounds.get(0);
            assertSame(location, played.location());
            assertEquals(SOUND_ID, played.id());
            assertEquals(volume, played.volume(), 0.000001F);
            assertEquals(pitch, played.pitch(), 0.000001F);
        }

        void assertSilent() {
            assertTrue(sounds.isEmpty(), "text delivery must not accidentally play a sound");
        }
    }

    private record PlayedSound(Location location, String id, float volume, float pitch) { }
}
