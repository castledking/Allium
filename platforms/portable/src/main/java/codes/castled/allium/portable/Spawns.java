package codes.castled.allium.portable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Server spawn storage shared by native Fabric and NeoForge adapters. */
public final class Spawns {
    private static final String KEY = "spawn";
    private final Path file;

    public Spawns(Path dataDirectory) {
        this.file = dataDirectory.resolve("spawn.properties");
    }

    public int set(Homes.Position position, Consumer<String> reply) {
        try {
            Properties props = new Properties();
            props.setProperty(KEY, position.encode());
            Path dir = file.getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "spawn-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    props.store(writer, null);
                }
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            reply.accept("Server spawn set.");
            return 1;
        } catch (IOException | IllegalArgumentException failure) {
            reply.accept("Spawn data could not be saved.");
            return 0;
        }
    }

    public Homes.Position resolve(Supplier<Homes.Position> fallback) {
        try {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (NoSuchFileException missing) {
                return fallback.get();
            }
            String raw = props.getProperty(KEY);
            return raw == null ? fallback.get() : Homes.Position.decode(raw);
        } catch (IOException | IllegalArgumentException failure) {
            return fallback.get();
        }
    }

    public int visit(Supplier<Homes.Position> fallback, Predicate<Homes.Position> teleport, Consumer<String> reply) {
        Homes.Position dest = resolve(fallback);
        if (dest == null || !teleport.test(dest)) {
            reply.accept("Could not teleport to spawn.");
            return 0;
        }
        reply.accept("Teleported to spawn.");
        return 1;
    }
}
