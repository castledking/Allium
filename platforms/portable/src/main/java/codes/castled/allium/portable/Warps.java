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
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Server-wide warps shared by native Fabric and NeoForge adapters. */
public final class Warps {
    private final Path file;

    public Warps(Path dataDirectory) {
        this.file = dataDirectory.resolve("warps.properties");
    }

    public int set(String rawName, Homes.Position position, Consumer<String> reply) {
        String name = normalize(rawName);
        if (name.isEmpty()) {
            reply.accept("Use 1-32 letters, digits, underscores or hyphens for a warp name.");
            return 0;
        }
        try {
            Properties warps = read();
            warps.setProperty(name, position.encode());
            save(warps);
            reply.accept("Warp '" + name + "' set.");
            return 1;
        } catch (IOException | IllegalArgumentException failure) {
            reply.accept("Warp data could not be saved.");
            return 0;
        }
    }

    public int visit(String rawName, Predicate<Homes.Position> teleport, Consumer<String> reply) {
        String name = normalize(rawName);
        if (name.isEmpty()) {
            reply.accept("Use 1-32 letters, digits, underscores or hyphens for a warp name.");
            return 0;
        }
        try {
            Properties warps = read();
            String saved = warps.getProperty(name);
            if (saved == null) {
                reply.accept("Warp '" + name + "' was not found.");
                return 0;
            }
            if (!teleport.test(Homes.Position.decode(saved))) {
                reply.accept("Could not warp to '" + name + "'. The destination may be unavailable.");
                return 0;
            }
            reply.accept("Warped to '" + name + "'.");
            return 1;
        } catch (IOException | IllegalArgumentException failure) {
            reply.accept("Warp data could not be read.");
            return 0;
        }
    }

    public int delete(String rawName, Consumer<String> reply) {
        String name = normalize(rawName);
        if (name.isEmpty()) {
            reply.accept("Use 1-32 letters, digits, underscores or hyphens for a warp name.");
            return 0;
        }
        try {
            Properties warps = read();
            if (warps.remove(name) == null) {
                reply.accept("Warp '" + name + "' was not found.");
                return 0;
            }
            save(warps);
            reply.accept("Warp '" + name + "' deleted.");
            return 1;
        } catch (IOException failure) {
            reply.accept("Warp data could not be saved.");
            return 0;
        }
    }

    public int list(Consumer<String> reply) {
        try {
            List<String> names = names(read(), "");
            reply.accept(names.isEmpty() ? "No warps are available." : "Warps: " + String.join(", ", names));
            return 1;
        } catch (IOException failure) {
            reply.accept("Warp data could not be read.");
            return 0;
        }
    }

    public List<String> suggestions(String prefix) {
        try {
            return names(read(), prefix == null ? "" : prefix.toLowerCase(Locale.ROOT));
        } catch (IOException failure) {
            return Collections.emptyList();
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.matches("[A-Za-z0-9_-]{1,32}") ? raw.toLowerCase(Locale.ROOT) : "";
    }

    private static List<String> names(Properties warps, String prefix) {
        List<String> names = new ArrayList<String>();
        for (String name : warps.stringPropertyNames()) {
            if (name.startsWith(prefix)) names.add(name);
        }
        Collections.sort(names);
        return names;
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
        Path tmp = Files.createTempFile(dir, "warps-", ".tmp");
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
