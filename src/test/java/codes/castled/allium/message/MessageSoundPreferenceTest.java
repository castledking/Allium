package codes.castled.allium.message;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.messaging.MessageSoundsCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceDefaults;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.preference.PreferenceStore;
import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageSoundPreferenceTest {

    @TempDir
    Path tempDir;

    @Test
    void mfxToggleMutesAndRestoresGeneralMessageSoundsAndBroadcasts() throws Exception {
        ConnectionSource source = () -> DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("prefs.db"));
        PreferenceRepository repo = new PreferenceRepository(source, SqlDialect.SQLITE, System::currentTimeMillis);
        repo.migrate();
        PreferenceStore store = new PreferenceStore(repo, 1, 64, id -> { });
        PreferenceDefaults defaults = new PreferenceDefaults(true, true, true, 1.0D);
        PreferenceSessions sessions = new PreferenceSessions(store, () -> defaults, msg -> { });
        try {
            UUID aliceId = UUID.randomUUID();
            UUID bobId = UUID.randomUUID();
            sessions.begin(aliceId);
            sessions.begin(bobId);
            sessions.whenReady(aliceId).get(5, TimeUnit.SECONDS);
            sessions.whenReady(bobId).get(5, TimeUnit.SECONDS);

            PluginStart plugin = mock(PluginStart.class);
            YamlConfiguration config = new YamlConfiguration();
            config.set("sounds.defaults.volume", 0.8D);
            config.set("sounds.defaults.pitch", 1.0D);
            config.set("sounds.messages.fallback-volume", 1.0D);
            when(plugin.getConfig()).thenReturn(config);

            PreferencesModule prefs = mock(PreferencesModule.class);
            when(prefs.isEnabled()).thenReturn(true);
            when(prefs.sessions()).thenReturn(sessions);
            when(prefs.defaults()).thenReturn(defaults);
            when(prefs.effective(aliceId)).thenAnswer(inv -> sessions.view(aliceId).map(PreferenceSessions.View::effective));
            when(prefs.effective(bobId)).thenAnswer(inv -> sessions.view(bobId).map(PreferenceSessions.View::effective));

            ModuleManager modules = mock(ModuleManager.class);
            when(modules.getModule("preferences")).thenReturn(prefs);
            when(plugin.getModuleManager()).thenReturn(modules);

            LanguageManager lang = mock(LanguageManager.class);
            when(lang.get(Messages.PREFIX)).thenReturn("&7[Allium] ");
            when(lang.get(Messages.HEAL)).thenReturn("{prefix}&aHealed!");
            when(lang.getSound(Messages.HEAL)).thenReturn(new MessageSoundSpec("minecraft:entity.experience_orb.pickup", null, 1.2D));
            when(lang.get(Messages.SOUNDS_MUTED)).thenReturn("{prefix}&cMessage sounds muted.");
            when(lang.getSound(Messages.SOUNDS_MUTED)).thenReturn(new MessageSoundSpec("minecraft:block.note_block.bass", null, 0.75D));
            when(lang.get(Messages.SOUNDS_RESTORED)).thenReturn("{prefix}&aMessage sounds are back on at {volume}.");
            when(lang.getSound(Messages.SOUNDS_RESTORED)).thenReturn(new MessageSoundSpec("minecraft:block.note_block.pling", null, 1.35D));
            when(lang.get(Messages.SOUNDS_FALLBACK)).thenReturn("{prefix}&aMessage sounds are back on at {volume}.");
            when(lang.getSound(Messages.SOUNDS_FALLBACK)).thenReturn(new MessageSoundSpec("minecraft:block.note_block.pling", null, 1.2D));
            when(lang.get(Messages.SOUNDS_VOLUME_SET)).thenReturn("{prefix}&aMessage sound volume set to {volume}.");
            when(lang.getSound(Messages.SOUNDS_VOLUME_SET)).thenReturn(new MessageSoundSpec("minecraft:block.note_block.pling", null, 1.35D));
            when(lang.get("custom.key", Messages.HEAL.getDefault())).thenReturn("{prefix}&aCustom!");
            when(lang.getSound("custom.key")).thenReturn(new MessageSoundSpec("minecraft:ui.button.click", 0.5D, 1.1D));

            MessageManager mm = new MessageManager(plugin, lang);
            when(plugin.getMessageManager()).thenReturn(mm);

            Location loc = new Location(null, 0, 64, 0);
            Player alice = mock(Player.class);
            when(alice.getUniqueId()).thenReturn(aliceId);
            when(alice.getName()).thenReturn("Alice");
            when(alice.getLocation()).thenReturn(loc);
            Player bob = mock(Player.class);
            when(bob.getUniqueId()).thenReturn(bobId);
            when(bob.getName()).thenReturn("Bob");
            when(bob.getLocation()).thenReturn(loc);

            Server server = mock(Server.class);
            doReturn(List.of(alice, bob)).when(server).getOnlinePlayers();
            when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));
            when(plugin.getServer()).thenReturn(server);

            MessageSoundsCommand command = new MessageSoundsCommand(plugin);

            // Before mute: general message sound plays at default volume (0.8 * 1.0).
            mm.send(alice, Messages.HEAL);
            verify(alice).playSound(eq(loc), eq("minecraft:entity.experience_orb.pickup"), eq(0.8f), eq(1.2f));
            clearInvocations(alice, bob);

            // /mfx toggle mutes immediately (including SOUNDS_MUTED itself) and silences send/LangMessage/broadcast.
            command.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MESSAGESOUNDS, alice, "mfx", new String[]{"toggle"}));
            mm.send(alice, Messages.HEAL);
            mm.send(alice, new LangMessage("custom.key", Messages.HEAL));
            mm.broadcast(Messages.HEAL);

            verify(alice, never()).playSound(eq(loc), anyString(), anyFloat(), anyFloat());
            verify(bob).playSound(eq(loc), eq("minecraft:entity.experience_orb.pickup"), eq(0.8f), eq(1.2f));
            clearInvocations(alice, bob);

            // /mfx toggle restores playback at the server default.
            command.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MESSAGESOUNDS, alice, "mfx", new String[]{"toggle"}));
            verify(alice).playSound(eq(loc), eq("minecraft:block.note_block.pling"), eq(0.8f), eq(1.2f));
            clearInvocations(alice);

            // /mfx volume 0.5 scales general message sounds, and toggle mutes then restores 0.5.
            command.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MESSAGESOUNDS, alice, "mfx", new String[]{"volume", "0.5"}));
            verify(alice).playSound(eq(loc), eq("minecraft:block.note_block.pling"), eq(0.4f), eq(1.35f));
            clearInvocations(alice);

            mm.send(alice, Messages.HEAL);
            verify(alice).playSound(eq(loc), eq("minecraft:entity.experience_orb.pickup"), eq(0.4f), eq(1.2f));
            clearInvocations(alice);

            command.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MESSAGESOUNDS, alice, "mfx", new String[]{"toggle"}));
            mm.send(alice, Messages.HEAL);
            verify(alice, never()).playSound(eq(loc), anyString(), anyFloat(), anyFloat());

            command.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MESSAGESOUNDS, alice, "mfx", new String[]{"toggle"}));
            verify(alice).playSound(eq(loc), eq("minecraft:block.note_block.pling"), eq(0.4f), eq(1.35f));
        } finally {
            store.close(5_000L);
        }
    }

    @Test
    void unloadedPreferencesFailClosedAndDisabledModuleUsesFallbackVolume() {
        UUID playerId = UUID.randomUUID();
        Location loc = new Location(null, 0, 64, 0);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(loc);

        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("sounds.defaults.volume", 1.0D);
        config.set("sounds.defaults.pitch", 1.0D);
        config.set("sounds.messages.fallback-volume", 0.5D);
        when(plugin.getConfig()).thenReturn(config);

        PreferencesModule prefs = mock(PreferencesModule.class);
        ModuleManager modules = mock(ModuleManager.class);
        when(modules.getModule("preferences")).thenReturn(prefs);
        when(plugin.getModuleManager()).thenReturn(modules);

        LanguageManager lang = mock(LanguageManager.class);
        when(lang.get(Messages.HEAL)).thenReturn("&aHealed!");
        when(lang.getSound(Messages.HEAL)).thenReturn(new MessageSoundSpec("minecraft:entity.experience_orb.pickup", 0.8D, 1.0D));

        MessageManager mm = new MessageManager(plugin, lang);

        // Enabled module with unloaded session -> silent (fail closed).
        when(prefs.isEnabled()).thenReturn(true);
        when(prefs.effective(playerId)).thenReturn(Optional.empty());
        mm.send(player, Messages.HEAL);
        verify(player, never()).playSound(eq(loc), anyString(), anyFloat(), anyFloat());

        // Enabled module with ready session at 0.25 -> 0.8 * 0.25 = 0.2.
        when(prefs.effective(playerId)).thenReturn(Optional.of(new EffectivePreferences(true, true, true, 0.25D, true)));
        mm.send(player, Messages.HEAL);
        verify(player).playSound(eq(loc), eq("minecraft:entity.experience_orb.pickup"), eq(0.2f), eq(1.0f));
        clearInvocations(player);

        // Disabled preferences module -> uses sounds.messages.fallback-volume (0.8 * 0.5 = 0.4).
        when(prefs.isEnabled()).thenReturn(false);
        mm.send(player, Messages.HEAL);
        verify(player).playSound(eq(loc), eq("minecraft:entity.experience_orb.pickup"), eq(0.4f), eq(1.0f));
        clearInvocations(player);

        // Disabled preferences module with fallback-volume = 0.0 -> silent.
        config.set("sounds.messages.fallback-volume", 0.0D);
        mm.send(player, Messages.HEAL);
        verify(player, never()).playSound(eq(loc), anyString(), anyFloat(), anyFloat());
    }
}
