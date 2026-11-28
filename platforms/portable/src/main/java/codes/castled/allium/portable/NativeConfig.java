package codes.castled.allium.portable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Loads {@code plugins/Allium/config.yml} and {@code plugins/Allium/lang/<locale>.yml} on native
 * Fabric (1.14–26.3) and NeoForge (1.20.2–26.3) servers, extracting bundled default files on first
 * start without overwriting user edits.
 */
public final class NativeConfig {
    private static final String RESOURCE_PREFIX = "allium-defaults/";
    private static final List<String> DEFAULT_FILES = Arrays.asList(
            "config.yml",
            "lang/en_US.yml",
            "lang/es_ES.yml",
            "info.txt",
            "rules.txt",
            "motd.txt",
            "custom.txt",
            "broadcasts.txt",
            "worth.yml"
    );

    private final Path dataDirectory;
    private volatile NativeYaml config = NativeYaml.empty();
    private volatile NativeYaml lang = NativeYaml.empty();
    private volatile String activeLocale = "en_US";

    public NativeConfig(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        reload();
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public synchronized void reload() {
        extractDefaults();
        try {
            config = NativeYaml.load(dataDirectory.resolve("config.yml"));
        } catch (IOException ignored) {
            config = NativeYaml.empty();
        }
        String requested = config.getString("settings.locale", "en_US").trim();
        if (!requested.matches("[A-Za-z0-9_-]{2,16}")) {
            requested = "en_US";
        }
        Path langFile = dataDirectory.resolve("lang").resolve(requested + ".yml");
        if (!Files.isRegularFile(langFile)) {
            requested = "en_US";
            langFile = dataDirectory.resolve("lang").resolve("en_US.yml");
        }
        activeLocale = requested;
        try {
            lang = NativeYaml.load(langFile);
        } catch (IOException ignored) {
            lang = NativeYaml.empty();
        }
    }

    public NativeYaml raw() {
        return config;
    }

    public NativeYaml langRaw() {
        return lang;
    }

    public String activeLocale() {
        return activeLocale;
    }

    public String prefix() {
        String p = lang.getString("prefix", "&8[&dAllium&8]&r");
        return NativePlayerInfo.translateColorCodes(p);
    }

    /** Formats a localized message from {@code plugins/Allium/lang/<locale>.yml}, expanding {@code {prefix}}. */
    public String format(String key, String fallback, String... placeholders) {
        String template = lang.getString(key + ".text", null);
        if (template == null) template = lang.getString(key, fallback);
        if (template == null) template = "";
        String prefixRaw = lang.getString("prefix", "&8[&dAllium&8]&r");
        String resolved = template.replace("{prefix}", prefixRaw);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String token = placeholders[i];
            String val = placeholders[i + 1] == null ? "" : placeholders[i + 1];
            if (token != null) resolved = resolved.replace(token, val);
        }
        return NativePlayerInfo.translateColorCodes(resolved);
    }

    public boolean isModuleEnabled(String module) {
        return config.getBoolean("modules." + module.toLowerCase(Locale.ROOT), true);
    }

    private void extractDefaults() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException ignored) {
            return;
        }
        ClassLoader cl = NativeConfig.class.getClassLoader();
        for (String rel : DEFAULT_FILES) {
            Path target = dataDirectory.resolve(rel);
            if (Files.exists(target)) continue;
            InputStream in = cl == null ? null : cl.getResourceAsStream(RESOURCE_PREFIX + rel);
            if (in == null && cl != null) {
                in = cl.getResourceAsStream(rel);
            }
            if (in == null) continue;
            try {
                try {
                    Files.createDirectories(target.getParent());
                    Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
                    try {
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                } finally {
                    in.close();
                }
            } catch (IOException ignored) {
            }
        }
        // Fallback minimal config.yml if running in an environment without bundled resources (e.g. unit tests)
        Path cfgFile = dataDirectory.resolve("config.yml");
        if (!Files.exists(cfgFile)) {
            writeFallback(cfgFile, "# Allium Configuration\nsettings:\n  locale: en_US\n");
        }
        Path enLang = dataDirectory.resolve("lang").resolve("en_US.yml");
        if (!Files.exists(enLang)) {
            writeFallback(enLang, "prefix: '&8[&dAllium&8]&r'\ncore:\n  reload-success: '{prefix} &aConfiguration reloaded.'\n");
        }
    }

    private static void writeFallback(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
