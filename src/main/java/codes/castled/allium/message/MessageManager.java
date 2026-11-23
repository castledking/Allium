package codes.castled.allium.message;

import codes.castled.allium.PluginStart;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.sound.SoundLevels;
import codes.castled.allium.text.FormattedText;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MessageManager {

    private final PluginStart plugin;
    private final LanguageManager langManager;
    private final Set<String> warnedSoundIds = ConcurrentHashMap.newKeySet();

    public MessageManager(PluginStart plugin, LanguageManager langManager) {
        this.plugin = plugin;
        this.langManager = langManager;
    }

    public void send(CommandSender sender, Messages message, String... replacements) {
        String text = resolve(message, replacements);
        if (!text.isEmpty()) {
            FormattedText formatted = TextFormatter.parse(text);
            TextSender.send(sender, formatted);
        }

        String actionBar = resolveActionBar(message, replacements);
        if (!actionBar.isEmpty()) {
            FormattedText formattedBar = TextFormatter.parse(actionBar);
            TextSender.sendActionBar(sender, formattedBar);
        }

        if (sender instanceof Player player) {
            playSoundIfPresent(player, message);
        }
    }

    /**
     * Sends only the {@code [actionbar]} portion of a message (silent, no sound, no chat line).
     * Returns {@code true} if the message has an action bar configured and it was sent.
     */
    public boolean sendActionBarUpdate(Player player, Messages message, String... replacements) {
        String actionBar = resolveActionBar(message, replacements);
        if (actionBar.isEmpty()) {
            return false;
        }
        TextSender.sendActionBar(player, TextFormatter.parse(actionBar));
        return true;
    }

    /** Clears a player's action bar when {@code message} is configured with {@code [actionbar]}. */
    public void clearActionBarIfPresent(Player player, Messages message) {
        String raw = langManager.getActionBar(message);
        if (raw != null && !raw.isEmpty()) {
            TextSender.clearActionBar(player);
        }
    }

    /** Sends the text and sound at a lang key; {@code send(s, LangMessage.of(m))} is {@code send(s, m)}. */
    public void send(CommandSender sender, LangMessage message, String... replacements) {
        if (message.key().equals(message.fallback().getKey())) {
            send(sender, message.fallback(), replacements);
            return;
        }
        String text = resolve(message, replacements);
        if (!text.isEmpty()) {
            FormattedText formatted = TextFormatter.parse(text);
            TextSender.send(sender, formatted);
        }

        String actionBar = resolveActionBar(message, replacements);
        if (!actionBar.isEmpty()) {
            TextSender.sendActionBar(sender, TextFormatter.parse(actionBar));
        }

        if (sender instanceof Player player) {
            MessageSoundSpec spec = langManager.getSound(message.key());
            if (spec != null) {
                playMessageSound(player, spec);
            }
        }
    }

    public void sendByKey(CommandSender sender, String key, String... replacements) {
        Messages message = Messages.fromKey(key);
        if (message != null) {
            send(sender, message, replacements);
        }
    }

    public void broadcast(Messages message, String... replacements) {
        String text = resolve(message, replacements);
        FormattedText formatted = text.isEmpty() ? FormattedText.EMPTY : TextFormatter.parse(text);
        String actionBar = resolveActionBar(message, replacements);
        FormattedText formattedBar = actionBar.isEmpty() ? FormattedText.EMPTY : TextFormatter.parse(actionBar);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Runnable deliver = () -> {
                if (!formatted.isEmpty()) TextSender.send(player, formatted);
                if (!formattedBar.isEmpty()) TextSender.sendActionBar(player, formattedBar);
                playSoundIfPresent(player, message);
            };
            if (BukkitServerThread.isFolia()) {
                BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
                    if (player.isOnline()) deliver.run();
                }, () -> {}, 0);
            } else {
                deliver.run();
            }
        }

        if (!formatted.isEmpty()) {
            plugin.getServer().getConsoleSender().sendMessage(formatted.toPlainText());
        } else if (!formattedBar.isEmpty()) {
            plugin.getServer().getConsoleSender().sendMessage(formattedBar.toPlainText());
        }
    }

    public void sendToPlayer(Player player, Messages message, String... replacements) {
        send(player, message, replacements);
    }

    public String resolve(Messages message, String... replacements) {
        String text = langManager.get(message);
        return formatResolved(text, replacements);
    }

    public String resolve(LangMessage message, String... replacements) {
        if (message.key().equals(message.fallback().getKey())) {
            return resolve(message.fallback(), replacements);
        }
        String text = langManager.get(message.key(), message.fallback().getDefault());
        return formatResolved(text, replacements);
    }

    public String resolveActionBar(Messages message, String... replacements) {
        String bar = langManager.getActionBar(message);
        return bar == null ? "" : formatResolved(bar, replacements);
    }

    public String resolveActionBar(LangMessage message, String... replacements) {
        if (message.key().equals(message.fallback().getKey())) {
            return resolveActionBar(message.fallback(), replacements);
        }
        String bar = langManager.getActionBar(message.key(), message.fallback().getDefault());
        return bar == null ? "" : formatResolved(bar, replacements);
    }

    private String formatResolved(String text, String... replacements) {
        if (text == null) {
            return "";
        }

        String prefix = langManager.get(Messages.PREFIX);
        if (prefix != null) {
            text = text.replace("{prefix}", prefix);
        }

        if (plugin.getDescription() != null && plugin.getDescription().getVersion() != null) {
            text = text.replace("{version}", plugin.getDescription().getVersion());
        }

        if (replacements.length % 2 == 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                text = text.replace(replacements[i], replacements[i + 1]);
            }
        }

        return text;
    }

    /**
     * Formats input using {@link TextFormatter#toLegacy(String)}.
     */
    public static String colorize(String input) {
        return TextFormatter.toLegacy(input);
    }

    /**
     * Plays a message's configured sound scaled by the player's {@code /mfx} message sound volume.
     */
    public void playSoundIfPresent(Player player, Messages message) {
        MessageSoundSpec spec = langManager.getSound(message);
        if (spec != null) {
            playMessageSound(player, spec);
        }
    }

    /**
     * Plays a message sound scaled by the player's {@code /mfx} volume override (or
     * {@code sounds.messages.fallback-volume} when preferences are unavailable/disabled).
     * Loading and failed preference sessions fail closed (silent).
     */
    public void playMessageSound(Player player, MessageSoundSpec spec) {
        if (player == null || spec == null) {
            return;
        }
        SoundLevels.Defaults defaults = soundDefaults(spec);
        double recipientVolume = playerMessageSoundVolume(player, defaults);
        if (recipientVolume <= 0.0D) {
            return;
        }
        SoundLevels.notification(recipientVolume, spec.volume(), spec.pitch(), defaults)
                .ifPresent(level -> play(player, spec, level));
    }

    private double playerMessageSoundVolume(Player player, SoundLevels.Defaults defaults) {
        if (plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule preferences
                && preferences.isEnabled()) {
            UUID uuid = player.getUniqueId();
            if (uuid == null) {
                return 0.0D;
            }
            // Loading and failed sessions are silent, never replaced with a default.
            return preferences.effective(uuid).map(EffectivePreferences::messageSoundVolume).orElse(0.0D);
        }
        return defaults.messageFallbackVolume();
    }

    /** Plays a message sound unscaled, at its own (or the default) volume and pitch. */
    public void playSound(Player player, MessageSoundSpec spec) {
        if (player == null || spec == null) {
            return;
        }
        SoundLevels.message(spec.volume(), spec.pitch(), soundDefaults(spec)).ifPresent(level -> play(player, spec, level));
    }

    /**
     * Plays a private-message notification sound, scaled by the recipient's message sound volume
     * ({@code EffectivePreferences#messageSoundVolume()}: their override, else
     * {@code sounds.messages.fallback-volume}). Callers pass a loaded value only; a recipient whose
     * preferences are not loaded gets no notification at all.
     */
    public void playNotificationSound(Player player, MessageSoundSpec spec, double recipientVolume) {
        if (player == null || spec == null) {
            return;
        }
        SoundLevels.notification(recipientVolume, spec.volume(), spec.pitch(), soundDefaults(spec)).ifPresent(level -> play(player, spec, level));
    }

    private SoundLevels.Defaults soundDefaults(MessageSoundSpec spec) {
        String problem = SoundLevels.problem(spec.volume(), spec.pitch());
        if (problem != null && warnedSoundIds.add("level:" + spec.soundId() + ":" + problem)) {
            plugin.logger().warning("Message sound '" + spec.soundId() + "': " + problem + "; the configured default is used instead.");
        }
        FileConfiguration config = plugin.getConfig();
        return SoundSettings.read(config, warning -> {
            if (warnedSoundIds.add("config:" + warning)) {
                plugin.logger().warning(warning);
            }
        });
    }

    private void play(Player player, MessageSoundSpec spec, SoundLevels.Level level) {
        float volume = level.volume();
        float pitch = level.pitch();
        Location loc = player.getLocation();
        if (loc == null) {
            return;
        }

        String soundId = spec.soundId();
        try {
            if (soundId.contains(":") || soundId.contains(".")) {
                player.playSound(loc, soundId.toLowerCase(Locale.ROOT), volume, pitch);
            } else {
                Sound bukkitSound = Sound.valueOf(soundId.toUpperCase(Locale.ROOT));
                player.playSound(loc, bukkitSound, volume, pitch);
            }
        } catch (IllegalArgumentException e) {
            if (warnedSoundIds.add(soundId)) {
                plugin.logger().warning("Invalid message sound ID '" + soundId + "': " + e.getMessage());
            }
        }
    }

    public Builder builder(Messages message) {
        return new Builder(message);
    }

    public final class Builder {
        private final Messages message;
        private final Map<String, String> replacements = new HashMap<>();

        Builder(Messages message) {
            this.message = message;
        }

        public Builder replace(String placeholder, String value) {
            replacements.put(placeholder, value);
            return this;
        }

        public Builder player(Player player) {
            replacements.put("{player}", player.getName());
            return this;
        }

        public Builder state(boolean enabled) {
            replacements.put("{state}", enabled ? "&aenabled" : "&cdisabled");
            return this;
        }

        public void send(CommandSender sender) {
            String[] flat = new String[replacements.size() * 2];
            int i = 0;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                flat[i++] = entry.getKey();
                flat[i++] = entry.getValue();
            }
            MessageManager.this.send(sender, message, flat);
        }

        public void broadcast() {
            String[] flat = new String[replacements.size() * 2];
            int i = 0;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                flat[i++] = entry.getKey();
                flat[i++] = entry.getValue();
            }
            MessageManager.this.broadcast(message, flat);
        }
    }
}
