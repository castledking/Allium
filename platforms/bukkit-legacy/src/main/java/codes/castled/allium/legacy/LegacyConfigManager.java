package codes.castled.allium.legacy;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Java 8 / 1.12.2-1.19.4 configuration manager reading and syncing Allium's real {@code config.yml}
 * and bundled default text/data resources ({@code rules.txt}, {@code motd.txt}, {@code info.txt},
 * {@code worth.yml}).
 */
final class LegacyConfigManager {

    private static final String[] DEFAULT_RESOURCES = {
            "config.yml",
            "rules.txt",
            "motd.txt",
            "info.txt",
            "custom.txt",
            "broadcasts.txt",
            "worth.yml"
    };

    private final JavaPlugin plugin;
    private FileConfiguration config = new YamlConfiguration();

    LegacyConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void loadAll() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        for (String res : DEFAULT_RESOURCES) {
            ensureResource(res);
        }
        File configFile = new File(dataFolder, "config.yml");
        YamlConfiguration loaded = configFile.isFile()
                ? YamlConfiguration.loadConfiguration(configFile)
                : new YamlConfiguration();

        InputStream bundledStream = plugin.getResource("config.yml");
        if (bundledStream != null) {
            try {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundledStream, StandardCharsets.UTF_8));
                loaded.setDefaults(defaults);
                int added = syncMissingKeys(loaded, defaults);
                if (added > 0 && configFile.isFile()) {
                    try {
                        loaded.save(configFile);
                        plugin.getLogger().info("Synced " + added + " missing key(s) into config.yml");
                    } catch (IOException e) {
                        plugin.getLogger().warning("Could not save synced config.yml: " + e.getMessage());
                    }
                }
            } finally {
                try { bundledStream.close(); } catch (IOException ignored) {}
            }
        }
        this.config = loaded;
    }

    void reloadAll() {
        loadAll();
    }

    FileConfiguration raw() {
        return config;
    }

    String locale() {
        String loc = config.getString("settings.locale", LegacyLanguageManager.DEFAULT_LOCALE);
        return loc == null || loc.trim().isEmpty() ? LegacyLanguageManager.DEFAULT_LOCALE : loc.trim();
    }

    boolean isModuleEnabled(String moduleKey) {
        if (moduleKey == null || moduleKey.isEmpty()) return true;
        return config.getBoolean("modules." + moduleKey.toLowerCase(Locale.ROOT), true);
    }

    Set<String> disabledCommands() {
        List<String> rawList = config.getStringList("disabled-commands");
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String entry : rawList) {
            if (entry == null) continue;
            String clean = entry.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith("/")) clean = clean.substring(1);
            if (clean.startsWith("allium:")) clean = clean.substring("allium:".length());
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    double soundDefaultVolume() {
        double v = config.getDouble("sounds.defaults.volume", 1.0);
        return Double.isFinite(v) && v >= 0.0 ? v : 1.0;
    }

    double soundDefaultPitch() {
        double p = config.getDouble("sounds.defaults.pitch", 1.0);
        return Double.isFinite(p) ? Math.max(0.5, Math.min(2.0, p)) : 1.0;
    }

    double messageSoundFallbackVolume() {
        double v = config.getDouble("sounds.messages.fallback-volume", 1.0);
        return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 1.0;
    }

    boolean scaleSentSound() {
        return config.getBoolean("sounds.messages.scale-sent-sound", true);
    }

    String currencySymbol() {
        String sym = config.getString("economy.currency-symbol", "$");
        return sym == null ? "$" : sym;
    }

    boolean currencySymbolSuffix() {
        return config.getBoolean("economy.currency-symbol-suffix", false);
    }

    BigDecimal startingBalance() {
        String raw = config.getString("economy.starting-balance", "100.00");
        BigDecimal parsed = LegacyCompat.parseMoneyAmount(raw);
        return parsed != null ? parsed : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    String formatCurrency(BigDecimal amount) {
        BigDecimal safe = amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
        String digits = String.format(Locale.US, "%,.2f", safe);
        String sym = currencySymbol();
        return currencySymbolSuffix() ? (digits + sym) : (sym + digits);
    }

    private void ensureResource(String resourcePath) {
        File target = new File(plugin.getDataFolder(), resourcePath);
        if (target.exists()) return;
        if ("custom.txt".equals(resourcePath) && new File(plugin.getDataFolder(), "customtext.txt").isFile()) return;
        if (plugin.getResource(resourcePath) != null) {
            try {
                plugin.saveResource(resourcePath, false);
            } catch (Throwable ignored) {
            }
        }
    }

    static int syncMissingKeys(YamlConfiguration target, YamlConfiguration defaults) {
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!target.contains(key)) {
                target.set(key, defaults.get(key));
                added++;
            }
        }
        return added;
    }
}
