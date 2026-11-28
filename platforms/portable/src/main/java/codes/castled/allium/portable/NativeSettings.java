package codes.castled.allium.portable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Per-player persisted settings (TPA, TP-auto, MSG, reply mode) and ignores for native mod platforms. */
public final class NativeSettings {
    public enum Toggle {
        TPA("tpa", true, "Teleport requests"),
        TP_AUTO("tpauto", false, "Auto-accepting teleport requests"),
        MSG("msg", true, "Private messages"),
        REPLY_LAST_RECIPIENT("rtoggle", false, "Reply to last recipient");

        public final String key;
        public final boolean defaultValue;
        public final String title;

        Toggle(String key, boolean defaultValue, String title) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.title = title;
        }
    }

    private final Path settingsFile;
    private final Path ignoresDir;

    public NativeSettings(Path dataDirectory) {
        this.settingsFile = dataDirectory.resolve("settings.properties");
        this.ignoresDir = dataDirectory.resolve("ignores");
    }

    public boolean get(UUID player, Toggle toggle) {
        try {
            Properties props = readProperties(settingsFile);
            String raw = props.getProperty(player + "." + toggle.key);
            return raw == null ? toggle.defaultValue : Boolean.parseBoolean(raw);
        } catch (IOException failure) {
            return toggle.defaultValue;
        }
    }

    public boolean set(UUID player, Toggle toggle, boolean value) {
        try {
            Properties props = readProperties(settingsFile);
            String k = player + "." + toggle.key;
            if (value == toggle.defaultValue) {
                props.remove(k);
            } else {
                props.setProperty(k, Boolean.toString(value));
            }
            saveProperties(settingsFile, props, "settings-");
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    public boolean isIgnoring(UUID owner, UUID target) {
        try {
            return readProperties(ignoreFile(owner)).containsKey(target.toString());
        } catch (IOException failure) {
            return false;
        }
    }

    public boolean ignore(UUID owner, UUID target, String targetName) {
        try {
            Path file = ignoreFile(owner);
            Properties props = readProperties(file);
            if (props.containsKey(target.toString())) return false;
            props.setProperty(target.toString(), targetName == null ? target.toString() : targetName);
            saveProperties(file, props, "ignore-");
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    public boolean unignore(UUID owner, UUID target) {
        try {
            Path file = ignoreFile(owner);
            Properties props = readProperties(file);
            if (props.remove(target.toString()) == null) return false;
            saveProperties(file, props, "ignore-");
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    public Map<UUID, String> ignoredPlayers(UUID owner) {
        try {
            Properties props = readProperties(ignoreFile(owner));
            List<String> keys = new ArrayList<String>(props.stringPropertyNames());
            Collections.sort(keys);
            Map<UUID, String> out = new LinkedHashMap<UUID, String>();
            for (String k : keys) {
                try {
                    out.put(UUID.fromString(k), props.getProperty(k, k));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return out;
        } catch (IOException failure) {
            return Collections.emptyMap();
        }
    }

    private Path ignoreFile(UUID owner) {
        return ignoresDir.resolve(owner + ".properties");
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (NoSuchFileException missing) {
            return props;
        }
        return props;
    }

    private static void saveProperties(Path file, Properties props, String prefix) throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, prefix, ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(writer, null);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
