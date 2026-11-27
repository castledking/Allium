package codes.castled.allium.legacy;

import codes.castled.allium.ServerVersion;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Java 8 / 1.12.2-1.19.4 message dispatcher with localized text, {@code [actionbar]} lines,
 * {@code [sound]} playback, and per-player {@code /messagesounds} ({@code /mfx}) volume preferences.
 */
final class LegacyMessageManager {

    private static final class SoundPref {
        Double override; // null = follow server default
        Double restore;  // last positive override before mute
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyLanguageManager lang;
    private final boolean pre113;
    private final Map<UUID, SoundPref> soundPrefs = new HashMap<UUID, SoundPref>();

    LegacyMessageManager(JavaPlugin plugin, LegacyConfigManager config, LegacyLanguageManager lang, ServerVersion version) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.pre113 = version != null && !version.isAtLeast(1, 13, 0);
    }

    void enable() {
        loadPrefs();
    }

    void reload() {
        loadPrefs();
    }

    void disable() {
        savePrefs();
    }

    /** Sends a localized message (text + optional {@code [actionbar]} + optional {@code [sound]}). */
    void send(CommandSender sender, String key, String defaultText, String... replacements) {
        sendInternal(sender, key, defaultText, false, replacements);
    }

    /** Returns the colored configured language prefix (e.g. {@code [Allium]}). */
    String prefix() {
        return LegacyCompat.color(lang.getPrefix());
    }

    /** Sends a command reply prefixed with the configured language prefix without double-prefixing. */
    void sendPrefixed(CommandSender sender, String coloredBody) {
        if (sender == null || coloredBody == null || coloredBody.isEmpty()) return;
        String p = prefix();
        if (p.isEmpty() || coloredBody.startsWith(p)) {
            sender.sendMessage(coloredBody);
        } else {
            sender.sendMessage(p + " " + coloredBody);
        }
    }

    /** Sends a private-message entry whose sound scales with the player's {@code /mfx} volume. */
    void sendPrivateMessageEntry(CommandSender sender, String key, String defaultText, boolean outgoing, String... replacements) {
        boolean scale = !outgoing || config.scaleSentSound();
        sendInternal(sender, key, defaultText, scale, replacements);
    }

    /** Sends only the {@code [actionbar]} portion of a message silently. */
    boolean sendActionBarUpdate(Player player, String key, String... replacements) {
        String bar = resolveActionBar(key, replacements);
        if (bar.isEmpty()) return false;
        LegacyCompat.sendActionBar(player, LegacyCompat.color(bar));
        return true;
    }

    /** Sends a raw colored actionbar string directly to a player. */
    void sendRawActionBar(Player player, String text) {
        if (player == null || text == null || text.isEmpty()) return;
        LegacyCompat.sendActionBar(player, LegacyCompat.color(text));
    }

    void clearActionBar(Player player) {
        LegacyCompat.clearActionBar(player);
    }

    String resolve(String key, String defaultText, String... replacements) {
        String raw = lang.getText(key, defaultText);
        return formatResolved(raw, replacements);
    }

    String resolveColored(String key, String defaultText, String... replacements) {
        return LegacyCompat.color(resolve(key, defaultText, replacements));
    }

    String resolveActionBar(String key, String... replacements) {
        String raw = lang.getActionBar(key);
        return raw == null || raw.isEmpty() ? "" : formatResolved(raw, replacements);
    }

    void playSoundSpec(Player player, LegacyLanguageManager.SoundSpec spec, boolean scaleByMessagePref) {
        if (player == null || spec == null) return;
        double baseVolume = spec.volume != null ? spec.volume.doubleValue() : config.soundDefaultVolume();
        double pitch = spec.pitch != null ? spec.pitch.doubleValue() : config.soundDefaultPitch();
        double finalVolume = baseVolume;
        if (scaleByMessagePref) {
            finalVolume = baseVolume * effectiveMessageSoundVolume(player.getUniqueId());
        }
        if (finalVolume <= 0.0) return;
        LegacyCompat.playSound(player, spec.soundId, (float) finalVolume, (float) pitch, pre113);
    }

    double effectiveMessageSoundVolume(UUID playerId) {
        SoundPref pref = soundPrefs.get(playerId);
        if (pref != null && pref.override != null) {
            return Math.max(0.0, Math.min(1.0, pref.override.doubleValue()));
        }
        return config.messageSoundFallbackVolume();
    }

    // ==================== /messagesounds (/mfx) ====================

    boolean handles(String name) {
        return "messagesounds".equals(name);
    }

    boolean onCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, "player-only", "{prefix} &cThis command can only be run by a player.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("allium.messagesounds") && !player.hasPermission("allium.msg")) {
            send(sender, "no-permission", "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", "messagesounds");
            return true;
        }
        if (!config.isModuleEnabled("preferences")) {
            send(sender, "messaging.sounds.disabled", "{prefix} &cMessage sound preferences are disabled on this server.");
            return true;
        }

        UUID id = player.getUniqueId();
        SoundPref pref = soundPrefs.get(id);
        double fallback = config.messageSoundFallbackVolume();
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (action.isEmpty()) {
            double effective = effectiveMessageSoundVolume(id);
            if (effective <= 0.0 && pref != null && pref.override != null) {
                double back = pref.restore != null && pref.restore.doubleValue() > 0.0
                        ? pref.restore.doubleValue() : fallback;
                send(player, "messaging.sounds.status-muted",
                        "{prefix} &7Message sounds are &cmuted&7. &f/mfx toggle &7turns them back on at &f{volume}&7.",
                        "{volume}", formatVolume(back));
            } else if (pref != null && pref.override != null) {
                send(player, "messaging.sounds.status-override",
                        "{prefix} &7Message sound volume: &f{volume} &7(your setting).",
                        "{volume}", formatVolume(effective));
            } else {
                send(player, "messaging.sounds.status-default",
                        "{prefix} &7Message sound volume: &f{volume} &7(the server default).",
                        "{volume}", formatVolume(effective));
            }
            send(player, "messaging.sounds.usage",
                    "{prefix} &7Use &f/mfx volume <0-1>&7, &f/mfx toggle &7or &f/mfx reset&7.");
            return true;
        }

        if ("volume".equals(action)) {
            if (args.length != 2) {
                send(player, "messaging.sounds.usage",
                        "{prefix} &7Use &f/mfx volume <0-1>&7, &f/mfx toggle &7or &f/mfx reset&7.");
                return true;
            }
            Double parsed = parseVolume(args[1]);
            if (parsed == null) {
                send(player, "messaging.sounds.volume-invalid",
                        "{prefix} &c'{input}' is not a volume from 0 to 1; nothing changed.",
                        "{input}", args[1]);
                return true;
            }
            SoundPref p = getOrCreatePref(id);
            if (parsed.doubleValue() > 0.0) {
                p.restore = parsed;
            } else if (p.override != null && p.override.doubleValue() > 0.0) {
                p.restore = p.override;
            }
            p.override = parsed;
            savePrefs();
            if (parsed.doubleValue() == 0.0) {
                send(player, "messaging.sounds.muted",
                        "{prefix} &cMessage sounds muted. &7/mfx toggle turns them back on.");
            } else {
                send(player, "messaging.sounds.volume-set",
                        "{prefix} &aMessage sound volume set to &f{volume}&a.",
                        "{volume}", formatVolume(parsed.doubleValue()));
            }
            return true;
        }

        if ("toggle".equals(action)) {
            SoundPref p = getOrCreatePref(id);
            double current = effectiveMessageSoundVolume(id);
            if (current > 0.0) {
                p.restore = Double.valueOf(current);
                p.override = Double.valueOf(0.0);
                savePrefs();
                send(player, "messaging.sounds.muted",
                        "{prefix} &cMessage sounds muted. &7/mfx toggle turns them back on.");
            } else if (p.restore != null && p.restore.doubleValue() > 0.0) {
                p.override = p.restore;
                savePrefs();
                send(player, "messaging.sounds.restored",
                        "{prefix} &aMessage sounds are back on at &f{volume}&a.",
                        "{volume}", formatVolume(p.override.doubleValue()));
            } else if (fallback > 0.0) {
                p.override = null;
                savePrefs();
                send(player, "messaging.sounds.fallback",
                        "{prefix} &aMessage sounds are back on at the server default, &f{volume}&a.",
                        "{volume}", formatVolume(fallback));
            } else {
                send(player, "messaging.sounds.fallback-silent",
                        "{prefix} &cThe server default volume is 0, so message sounds stay muted. &7Use &f/mfx volume <0-1>&7.");
            }
            return true;
        }

        if ("reset".equals(action)) {
            soundPrefs.remove(id);
            savePrefs();
            send(player, "messaging.sounds.reset",
                    "{prefix} &aYou now follow the server's message sound volume, &f{volume}&a.",
                    "{volume}", formatVolume(fallback));
            return true;
        }

        send(player, "messaging.sounds.usage",
                "{prefix} &7Use &f/mfx volume <0-1>&7, &f/mfx toggle &7or &f/mfx reset&7.");
        return true;
    }

    List<String> onTabComplete(String[] args, String prefix) {
        if (args.length == 1) {
            List<String> out = new ArrayList<String>();
            for (String s : Arrays.asList("toggle", "volume", "reset")) {
                if (s.startsWith(prefix)) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && "volume".equalsIgnoreCase(args[0])) {
            List<String> out = new ArrayList<String>();
            for (String s : Arrays.asList("0", "0.25", "0.5", "0.75", "1")) {
                if (s.startsWith(prefix)) out.add(s);
            }
            return out;
        }
        return Collections.emptyList();
    }

    /** Plays a message's configured sound alone, without its text. */
    void playSound(Player player, String key) {
        LegacyLanguageManager.SoundSpec sound = lang.getSound(key);
        if (sound != null) playSoundSpec(player, sound, false);
    }

    private void sendInternal(CommandSender sender, String key, String defaultText, boolean scaleSound, String... replacements) {
        sendInternal(sender, key, defaultText, scaleSound, null, replacements);
    }

    void sendWithLiteralBody(CommandSender sender, String key, String defaultText, String body, String... replacements) {
        sendInternal(sender, key, defaultText, false, body, replacements);
    }

    private void sendInternal(CommandSender sender, String key, String defaultText, boolean scaleSound,
                              String literalBody, String... replacements) {
        if (sender == null) return;
        String text = resolve(key, defaultText, replacements);
        if (!text.isEmpty()) {
            String colored = LegacyCompat.color(text);
            for (String line : colored.split("\n", -1)) {
                sender.sendMessage(literalBody == null ? line : line.replace("{message}", literalBody));
            }
        }
        String bar = resolveActionBar(key, replacements);
        if (!bar.isEmpty() && sender instanceof Player) {
            String colored = LegacyCompat.color(bar);
            LegacyCompat.sendActionBar((Player) sender, literalBody == null ? colored : colored.replace("{message}", literalBody));
        }
        if (sender instanceof Player) {
            LegacyLanguageManager.SoundSpec sound = lang.getSound(key);
            if (sound != null) {
                playSoundSpec((Player) sender, sound, scaleSound);
            }
        }
    }

    private String formatResolved(String template, String... replacements) {
        if (template == null || template.isEmpty()) return "";
        String out = template.replace("{prefix}", lang.getPrefix());
        if (replacements != null) {
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                String k = replacements[i];
                String v = replacements[i + 1];
                if (k != null) {
                    out = out.replace(k, v == null ? "" : v);
                }
            }
        }
        return out;
    }

    private SoundPref getOrCreatePref(UUID id) {
        SoundPref pref = soundPrefs.get(id);
        if (pref == null) {
            pref = new SoundPref();
            soundPrefs.put(id, pref);
        }
        return pref;
    }

    private static Double parseVolume(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.trim();
        boolean pct = s.endsWith("%");
        if (pct) s = s.substring(0, s.length() - 1).trim();
        try {
            double val = Double.parseDouble(s);
            if (!Double.isFinite(val)) return null;
            if (pct) val /= 100.0;
            if (val < 0.0 || val > 1.0) return null;
            return Double.valueOf(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatVolume(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private void loadPrefs() {
        soundPrefs.clear();
        File file = new File(plugin.getDataFolder(), "preferences-legacy.yml");
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("message-sounds");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                SoundPref pref = new SoundPref();
                if (s.contains("override")) pref.override = Double.valueOf(s.getDouble("override"));
                if (s.contains("restore")) pref.restore = Double.valueOf(s.getDouble("restore"));
                soundPrefs.put(id, pref);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void savePrefs() {
        File file = new File(plugin.getDataFolder(), "preferences-legacy.yml");
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, SoundPref> e : soundPrefs.entrySet()) {
            String path = "message-sounds." + e.getKey();
            if (e.getValue().override != null) yml.set(path + ".override", e.getValue().override);
            if (e.getValue().restore != null) yml.set(path + ".restore", e.getValue().restore);
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save preferences-legacy.yml: " + e.getMessage());
        }
    }
}
