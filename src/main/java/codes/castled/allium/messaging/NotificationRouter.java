package codes.castled.allium.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageSoundSpec;
import codes.castled.allium.message.SoundSettings;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** One literal component delivery and at most one sound for each notification. */
public final class NotificationRouter implements NotificationService {
    private final PluginStart plugin;

    public NotificationRouter(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public void send(CommandSender recipient, Notification notification) {
        List<String> replacements = new ArrayList<>();
        notification.placeholders().forEach((key, value) -> {
            replacements.add(key);
            replacements.add(value);
        });
        String template = plugin.getMessageManager().resolve(notification.message(), replacements.toArray(String[]::new));
        if (!template.isEmpty()) {
            TextSender.sendLiteral(recipient, notification.body() == null ? TextFormatter.parse(template)
                    : UntrustedText.insert(template, notification.body()));
        }
        if (!(recipient instanceof Player player) || notification.sound() == Sound.NONE) {
            return;
        }
        MessageSoundSpec sound = plugin.getLanguageManager().getSound(notification.message());
        if (sound == null) {
            return;
        }
        if (notification.sound() == Sound.SENT && !plugin.getConfig().getBoolean("sounds.messages.scale-sent-sound", true)) {
            plugin.getMessageManager().playSound(player, sound);
            return;
        }
        double volume;
        if (plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule preferences && preferences.isEnabled()) {
            // Loading and failed sessions are silent, never replaced with a default.
            volume = preferences.effective(player.getUniqueId()).map(EffectivePreferences::messageSoundVolume).orElse(0.0D);
        } else {
            volume = SoundSettings.read(plugin.getConfig(), ignored -> { }).messageFallbackVolume();
        }
        if (volume > 0.0D) {
            plugin.getMessageManager().playNotificationSound(player, sound, volume);
        }
    }
}
