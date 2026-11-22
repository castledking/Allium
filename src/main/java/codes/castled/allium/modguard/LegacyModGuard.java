package codes.castled.allium.modguard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Carries legacy Allium's ModGuard settings into modguard.yml on its first start.
 *
 * <p>Legacy kept them in {@code modguard/config.yml} and {@code modguard/rules.yml}; older legacy
 * versions had a {@code modguard} section in config.yml instead, with a list of banned mods rather
 * than rules. Both are read. Settings with the same meaning are copied; each banned mod becomes a
 * rule that refuses to join (or the existing rule for that mod is set to); the rest are reported.
 */
final class LegacyModGuard {
    record Result(List<String> copied, List<String> retired) {}

    /** Settings whose meaning did not change. Whole sections are copied with everything under them. */
    static final List<String> SAME = List.of("enabled", "staff-permission", "bypass-permission", "kick-message",
            "staff-alert", "debug", "alerts.minimum-severity", "access-control", "lunar", "translation-probe");

    /** Legacy settings with no counterpart, and why. */
    static final Map<String, String> RETIRED = Map.ofEntries(
            Map.entry("mode", "ModGuard matches clients against rules; there is no allow-list mode"),
            Map.entry("allowed-mods", "ModGuard matches clients against rules; there is no allow-list mode"),
            Map.entry("check-on-join", "clients are always checked when they join"),
            Map.entry("check-delay-ticks", "clients are checked as their channels arrive"),
            Map.entry("check-brand", "the brand is always checked"),
            Map.entry("check-channels", "channels are always checked"),
            Map.entry("check-fml-handshake", "Forge's mod list is always read"),
            Map.entry("log-all-mods", "every detection is written to the console"),
            Map.entry("log-detections", "every detection is written to the console"),
            Map.entry("detection-log", "every detection is written to the console"),
            Map.entry("notify-staff", "staff without staff-permission get no alerts"),
            Map.entry("regex-matching", "rules use * wildcards"),
            Map.entry("case-sensitive", "rules always ignore case"),
            Map.entry("config-version", "legacy's own version stamp"),
            Map.entry("server-name", "alerts name the player, not the server"),
            Map.entry("staffchat", "alerts go to staff with staff-permission"),
            Map.entry("discord", "ModGuard does not post to Discord"),
            Map.entry("updates", "there is no update checker"),
            Map.entry("alerts.local-log", "every detection is written to the console"));

    private LegacyModGuard() {}

    /**
     * Writes {@code settings} (legacy modguard/config.yml, or config.yml's modguard section) and
     * {@code rulesFile} (legacy rules.yml, or null) into {@code target}, bundled modguard.yml.
     */
    static Result apply(ConfigurationSection settings, ConfigurationSection rulesFile, YamlConfiguration target) {
        List<String> copied = new ArrayList<>();
        List<String> retired = new ArrayList<>();
        if (rulesFile != null && rulesFile.isConfigurationSection("rules")) {
            target.set("rules", null);
            ConfigurationSection rules = rulesFile.getConfigurationSection("rules");
            for (String id : rules.getKeys(false)) {
                ConfigurationSection rule = rules.getConfigurationSection(id);
                if (rule == null) continue;
                for (String key : rule.getKeys(true)) {
                    // join-resources were never read by legacy's rule engine.
                    if (key.startsWith("join-resources") || rule.isConfigurationSection(key)) continue;
                    target.set("rules." + id + "." + key, rule.get(key));
                }
            }
            if (rulesFile.isSet("signature-pack-version")) target.set("signature-pack-version", rulesFile.get("signature-pack-version"));
            copied.add("rules (" + rules.getKeys(false).size() + ")");
        }
        if (settings == null) return new Result(List.copyOf(copied), List.copyOf(retired));
        for (String path : settings.getKeys(true)) {
            if (settings.isConfigurationSection(path)) continue;
            Object value = settings.get(path);
            if (under(path, SAME)) {
                target.set(path, value);
                copied.add(path);
            } else if (path.equals("kick-on-detection")) {
                target.set("access-control.enabled", value);
                copied.add("kick-on-detection -> access-control.enabled");
            } else if (path.equals("banned-mods")) {
                for (String mod : settings.getStringList(path)) copied.add("banned-mods " + mod + " -> " + ban(target, mod));
            } else {
                String top = path.split("\\.", 2)[0];
                String reason = RETIRED.containsKey(path) ? RETIRED.get(path) : RETIRED.get(top);
                retired.add(path + ": " + (reason == null ? "not a ModGuard setting" : reason));
            }
        }
        return new Result(List.copyOf(copied), List.copyOf(retired));
    }

    /** Refuses a banned mod: the rule that already matches it, or a new CRITICAL rule for it. */
    private static String ban(YamlConfiguration target, String mod) {
        String id = mod.trim().toLowerCase(Locale.ROOT);
        ConfigurationSection rules = target.getConfigurationSection("rules");
        if (rules != null) {
            for (String rule : rules.getKeys(false)) {
                for (String pattern : rules.getStringList(rule + ".mods")) {
                    if (Detection.glob(pattern).matcher(id).matches()) {
                        target.set("access-control.mods." + rule + ".allow-join", false);
                        return "rule " + rule;
                    }
                }
            }
        }
        String rule = "banned-" + id.replaceAll("[^a-z0-9_-]", "_");
        target.set("rules." + rule + ".enabled", true);
        target.set("rules." + rule + ".name", mod.trim());
        target.set("rules." + rule + ".severity", "CRITICAL");
        target.set("rules." + rule + ".mods", List.of(id));
        target.set("access-control.mods." + rule + ".allow-join", false);
        return "new rule " + rule;
    }

    private static boolean under(String path, List<String> roots) {
        for (String root : roots) if (path.equals(root) || path.startsWith(root + ".")) return true;
        return false;
    }
}
