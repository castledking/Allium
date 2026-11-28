package codes.castled.allium.portable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Atomic flatfile item worth table shared by native Fabric (1.14–26.3) and NeoForge (1.20.2–26.3) adapters.
 * Persists item unit prices in {@code <world>/allium/worth.properties}.
 */
public final class NativeWorth {
    private static final Object LOCK = new Object();
    private final Path file;

    public NativeWorth(Path dataDirectory) {
        this.file = dataDirectory.resolve("worth.properties");
    }

    public static String normalizeItemId(String raw) {
        if (raw == null) return "";
        String clean = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (clean.isEmpty()) return "";
        if (clean.indexOf(':') < 0) {
            clean = "minecraft:" + clean;
        }
        return clean.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") ? clean : "";
    }

    public BigDecimal priceOf(String rawItemId) {
        String id = normalizeItemId(rawItemId);
        if (id.isEmpty()) return null;
        synchronized (LOCK) {
            try {
                BigDecimal parsed = NativeEconomy.parseAmount(read().getProperty(id));
                return parsed != null && parsed.compareTo(BigDecimal.ZERO) > 0 ? parsed : null;
            } catch (IOException failure) {
                return null;
            }
        }
    }

    public boolean setPrice(String rawItemId, BigDecimal price) {
        String id = normalizeItemId(rawItemId);
        if (id.isEmpty() || price == null || price.compareTo(BigDecimal.ZERO) < 0) return false;
        synchronized (LOCK) {
            try {
                Properties props = read();
                if (price.compareTo(BigDecimal.ZERO) == 0) {
                    props.remove(id);
                } else {
                    props.setProperty(id, price.setScale(2, RoundingMode.HALF_UP).toPlainString());
                }
                save(props);
                return true;
            } catch (IOException failure) {
                return false;
            }
        }
    }

    public List<String> suggestions(String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        synchronized (LOCK) {
            try {
                Properties props = read();
                List<String> out = new ArrayList<String>();
                for (String key : props.stringPropertyNames()) {
                    if (key.startsWith(lower) || (key.startsWith("minecraft:") && key.substring("minecraft:".length()).startsWith(lower))) {
                        out.add(key);
                    }
                }
                Collections.sort(out);
                return out;
            } catch (IOException failure) {
                return Collections.emptyList();
            }
        }
    }

    private Properties read() throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (NoSuchFileException missing) {
            return props;
        }
        return props;
    }

    private void save(Properties props) throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "worth-", ".tmp");
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
