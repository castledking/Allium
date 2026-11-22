package codes.castled.allium.addon;

import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of official Allium addons published alongside Allium on GitHub Releases
 * ({@code Addon-<DisplayName>-<Version>.jar}).
 */
public final class AddonCatalog {

    public static final String DEFAULT_RELEASE_BASE_URL = "https://github.com/castledking/Allium/releases/download";

    public record Entry(String key, String displayName, String description, List<String> aliases) {
        public String jarFileName(String version) {
            return "Addon-" + displayName + "-" + version + ".jar";
        }
    }

    private static final List<Entry> OFFICIAL = List.of(
            new Entry("crops", "Crops", "Custom crops, fertilizers, sprinklers, kitchen & pies", List.of("harvest")),
            new Entry("curios", "Curios", "Trading cards, relics equipment, morphs & hover frames", List.of("tradingcards", "relics")),
            new Entry("ores", "Ores", "CraftEngine custom ore generation & /pregen", List.of()),
            new Entry("partylocator", "PartyLocator", "Parties & 1.21.6+ locator bar integration", List.of("party-locator", "party")),
            new Entry("spawnercraft", "SpawnerCraft", "Mob head drops, spawner changers & recipe guides", List.of())
    );

    private static final Map<String, Entry> BY_TOKEN = new LinkedHashMap<>();
    static {
        for (Entry e : OFFICIAL) {
            BY_TOKEN.put(e.key().toLowerCase(Locale.ROOT), e);
            BY_TOKEN.put(e.displayName().toLowerCase(Locale.ROOT), e);
            for (String alias : e.aliases()) {
                BY_TOKEN.put(alias.toLowerCase(Locale.ROOT), e);
            }
        }
    }

    private AddonCatalog() {}

    public static List<Entry> official() {
        return OFFICIAL;
    }

    public static Optional<Entry> find(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        return Optional.ofNullable(BY_TOKEN.get(raw.trim().toLowerCase(Locale.ROOT)));
    }

    /** Canonical config key under {@code addons.enabled.<key>}. */
    public static String configKey(String name) {
        return find(name).map(Entry::key).orElse(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves the release version string (without leading {@code v}) used in release tags and jar names.
     */
    public static String releaseVersion(@Nullable String pluginVersion, @Nullable String configuredOverride) {
        if (configuredOverride != null && !configuredOverride.isBlank()) {
            String trimmed = configuredOverride.trim();
            return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
        }
        if (pluginVersion == null || pluginVersion.isBlank()) return "1.0.0";
        String clean = pluginVersion.trim();
        int dash = clean.indexOf('-');
        if (dash > 0) clean = clean.substring(0, dash);
        if (clean.startsWith("v") || clean.startsWith("V")) clean = clean.substring(1);
        return clean.isEmpty() ? "1.0.0" : clean;
    }

    /**
     * Builds the GitHub Releases download URL, e.g.
     * {@code https://github.com/castledking/Allium/releases/download/v1.0.0/Addon-Curios-1.0.0.jar}.
     */
    public static String downloadUrl(@Nullable String baseUrl, String version, Entry entry) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_RELEASE_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String tag = version.startsWith("v") || version.startsWith("V") ? version : "v" + version;
        String cleanVersion = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        return base + "/" + tag + "/" + entry.jarFileName(cleanVersion);
    }

    /**
     * Returns whether an addon jar in {@code plugins/Allium/addons/} is allowed to start.
     *
     * <p>If {@code addons.enabled.<key>} is explicitly set in {@code config.yml}, that value governs
     * whether the jar starts (allowing a jar to stay installed in {@code addons/} while disabled).
     * When unset (for example, a third-party addon or a test with no config), loading is allowed.
     */
    public static boolean isEnabledInConfig(@Nullable FileConfiguration config, String pluginName) {
        if (config == null || pluginName == null || pluginName.isBlank()) return true;
        String key = configKey(pluginName);
        String direct = "addons.enabled." + key;
        if (config.isSet(direct)) return config.getBoolean(direct, true);
        Optional<Entry> entry = find(pluginName);
        if (entry.isPresent()) {
            for (String alias : entry.get().aliases()) {
                String aliasPath = "addons.enabled." + alias;
                if (config.isSet(aliasPath)) return config.getBoolean(aliasPath, true);
            }
        }
        String rawPath = "addons.enabled." + pluginName.trim();
        if (config.isSet(rawPath)) return config.getBoolean(rawPath, true);
        return true;
    }

    /** Returns whether an official addon is explicitly set to {@code true} in {@code addons.enabled.*}. */
    public static boolean isExplicitlyEnabled(@Nullable FileConfiguration config, Entry entry) {
        if (config == null) return false;
        String direct = "addons.enabled." + entry.key();
        if (config.isSet(direct)) return config.getBoolean(direct, false);
        for (String alias : entry.aliases()) {
            String aliasPath = "addons.enabled." + alias;
            if (config.isSet(aliasPath)) return config.getBoolean(aliasPath, false);
        }
        return false;
    }
}
