package codes.castled.allium.message;

import codes.castled.allium.PluginStart;
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

        if (sender instanceof Player player) {
            playSoundIfPresent(player, message);
        }
    }

    public void broadcast(Messages message, String... replacements) {
        String text = resolve(message, replacements);
        FormattedText formatted = text.isEmpty() ? FormattedText.EMPTY : TextFormatter.parse(text);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!formatted.isEmpty()) {
                TextSender.send(player, formatted);
            }
            playSoundIfPresent(player, message);
        }

        if (!formatted.isEmpty()) {
            plugin.getServer().getConsoleSender().sendMessage(formatted.toPlainText());
        }
    }

    public void sendToPlayer(Player player, Messages message, String... replacements) {
        send(player, message, replacements);
    }

    public String resolve(Messages message, String... replacements) {
        String text = langManager.get(message);
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
     * Plays a message's configured sound directly using Bukkit's sound API.
     */
    public void playSoundIfPresent(Player player, Messages message) {
        MessageSoundSpec spec = langManager.getSound(message);
        if (spec != null) {
            playSound(player, spec);
        }
    }

    public void playSound(Player player, MessageSoundSpec spec) {
        if (player == null || spec == null) {
            return;
        }
        FileConfiguration config = plugin.getConfig();
        double defaultVol = config != null ? config.getDouble("sounds.defaults.volume", 1.0) : 1.0;
        double defaultPitch = config != null ? config.getDouble("sounds.defaults.pitch", 1.0) : 1.0;

        double rawVol = spec.volume() != null ? spec.volume() : defaultVol;
        double rawPitch = spec.pitch() != null ? spec.pitch() : defaultPitch;

        if (!Double.isFinite(rawVol) || rawVol <= 0.0) {
            return;
        }
        if (!Double.isFinite(rawPitch) || rawPitch <= 0.0) {
            rawPitch = 1.0;
        }
        float volume = (float) rawVol;
        float pitch = (float) Math.max(0.5, Math.min(2.0, rawPitch));

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
