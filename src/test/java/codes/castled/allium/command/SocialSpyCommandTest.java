package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.messaging.SocialSpyCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SocialSpyCommandTest {
    @Test
    void selfOnlySyntaxNeverChangesPreferences() {
        PluginStart plugin = mock(PluginStart.class);
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        SocialSpyCommand command = new SocialSpyCommand(plugin);
        Player self = mock(Player.class);
        for (String[] args : new String[][]{{"maybe"}, {"on", "other"}, {"off", "extra", "extra"}}) {
            command.execute(context(plugin, self, args));
        }
        command.execute(context(plugin, mock(ConsoleCommandSender.class), new String[]{"on"}));
        verify(plugin, never()).getModuleManager();
        verify(messages, times(4)).sendByKey(any(), anyString(), eq("{usage}"), eq("/socialspy [on|off]"));
    }

    @Test
    void togglesPersistAndExplicitStateIsIdempotentWhileUnloadedFailsClosed() {
        PluginStart plugin = mock(PluginStart.class);
        ModuleManager modules = mock(ModuleManager.class);
        MessageManager messages = mock(MessageManager.class);
        PreferencesModule preferences = mock(PreferencesModule.class);
        PreferenceSessions sessions = mock(PreferenceSessions.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(plugin.getModuleManager()).thenReturn(modules);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(modules.getModule("preferences")).thenReturn(preferences);
        when(preferences.isEnabled()).thenReturn(true);
        when(preferences.sessions()).thenReturn(sessions);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Spy");
        when(preferences.effective(uuid)).thenReturn(Optional.of(new EffectivePreferences(true, true, true, 1, false, false)));
        when(sessions.change(eq(uuid), any())).thenReturn(CompletableFuture.completedFuture(StoredPreferences.empty(uuid)));
        SocialSpyCommand command = new SocialSpyCommand(plugin);
        command.execute(context(plugin, player, new String[]{"off"}));
        verify(sessions, never()).change(any(), any());
        command.execute(context(plugin, player, new String[]{}));
        verify(sessions).change(eq(uuid), argThat(patch -> Boolean.TRUE.equals(patch.entries().get(PreferenceKey.SOCIAL_SPY).orElse(null))));
        when(preferences.effective(uuid)).thenReturn(Optional.empty());
        command.execute(context(plugin, player, new String[]{"on"}));
        verify(sessions, times(1)).change(any(), any());
        verify(messages).send(player, Messages.PREFERENCES_UNAVAILABLE, "{player}", "Spy");
        when(preferences.isEnabled()).thenReturn(false);
        command.execute(context(plugin, player, new String[]{"on"}));
        verify(messages).send(player, Messages.SOCIALSPY_DISABLED);
    }

    @Test
    void commandAndResourcesKeepExemptionExplicitAndLoggingOff() throws Exception {
        assertEquals("allium.socialspy", CoreCommandSpecs.SOCIALSPY.basePermission());
        assertTrue(CoreCommandSpecs.MESSAGING_SPECS.contains(CoreCommandSpecs.SOCIALSPY));
        YamlConfiguration plugin = resource("plugin.yml");
        assertEquals("op", plugin.getString("permissions.allium.socialspy.default"));
        assertFalse(plugin.getBoolean("permissions.allium.socialspy.exempt.default"));
        assertFalse(plugin.contains("permissions.allium.*.children.allium.socialspy.exempt"));
        assertFalse(resource("config.yml").getBoolean("messaging.socialspy.log-to-console", true));
        for (String language : new String[]{"en_US", "es_ES"}) {
            YamlConfiguration lang = resource("lang/" + language + ".yml");
            assertTrue(lang.getString(Messages.SOCIALSPY_MESSAGE.getKey()).contains("{message}"));
            assertNotNull(lang.getString(Messages.SOCIALSPY_ON.getKey()));
            assertNotNull(lang.getString(Messages.SOCIALSPY_OFF.getKey()));
            assertNotNull(lang.getString(Messages.SOCIALSPY_DISABLED.getKey()));
            assertNotNull(lang.getString(Messages.SOCIALSPY_SAVE_FAILED.getKey()));
            assertNotNull(lang.getString("messaging.received.text"), "existing messaging translation retained");
        }
    }

    private static BukkitCommandContext context(PluginStart plugin, org.bukkit.command.CommandSender sender, String[] args) {
        return new BukkitCommandContext(plugin, CoreCommandSpecs.SOCIALSPY, sender, "socialspy", args);
    }

    private static YamlConfiguration resource(String path) throws Exception {
        try (var stream = SocialSpyCommandTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        }
    }
}
