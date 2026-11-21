package codes.castled.allium.explosion;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One file per explosion still waiting to come back, in {@code plugins/Allium/regeneration/}: the
 * blocks left to put back and the block entity data a crash would otherwise lose. A file is written
 * (and flushed to disk) before the explosion's blocks leave the world, rewritten once a block that
 * held items is resolved, and deleted when the last block is back. A server that stopped without
 * finishing reads them at startup and finishes the job.
 *
 * <p>No server API is needed to read or write a journal.
 */
public final class RegenJournal {

    public static final int FORMAT = 1;
    private static final String SUFFIX = ".yml";

    /** An explosion's remaining blocks, in restore order. */
    public record Entry(UUID id, UUID world, String worldName, double centerX, double centerY, double centerZ,
                        long created, List<CapturedBlock> blocks) {
        public Entry {
            blocks = List.copyOf(blocks);
        }
    }

    private final Path folder;

    public RegenJournal(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    public void write(Entry entry) throws IOException {
        Files.createDirectories(folder);
        byte[] bytes = encode(entry).getBytes(StandardCharsets.UTF_8);
        Path file = file(entry.id());
        Path temp = Files.createTempFile(folder, ".regen-", ".tmp");
        try {
            Files.write(temp, bytes);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        syncFolder();
    }

    public void delete(UUID id) throws IOException {
        Files.deleteIfExists(file(id));
    }

    /** Every journal on disk; a file that cannot be read is renamed {@code .broken} with a warning. */
    public List<Entry> readAll(Consumer<String> warn) {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(folder)) return entries;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + SUFFIX)) {
            for (Path file : files) {
                try {
                    entries.add(decode(Files.readString(file, StandardCharsets.UTF_8)));
                } catch (IOException | InvalidConfigurationException | RuntimeException unreadable) {
                    warn.accept("Regeneration journal " + file.getFileName() + " could not be read and was set aside: "
                            + unreadable.getMessage());
                    try {
                        Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {
                        // Left in place; it is reported again next start.
                    }
                }
            }
        } catch (IOException failed) {
            warn.accept("The regeneration journals in " + folder + " could not be listed: " + failed.getMessage());
        }
        return entries;
    }

    public static String encode(Entry entry) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        yaml.set("id", entry.id().toString());
        yaml.set("world", entry.world().toString());
        yaml.set("world-name", entry.worldName());
        yaml.set("center", entry.centerX() + " " + entry.centerY() + " " + entry.centerZ());
        yaml.set("created", entry.created());
        List<String> lines = new ArrayList<>(entry.blocks().size());
        for (int i = 0; i < entry.blocks().size(); i++) {
            CapturedBlock block = entry.blocks().get(i);
            lines.add(block.x() + " " + block.y() + " " + block.z() + " " + (block.attached() ? 1 : 0) + " " + block.data());
            if (block.tile() != null) block.tile().write(yaml.createSection("tiles." + i));
        }
        yaml.set("blocks", lines);
        return yaml.saveToString();
    }

    public static Entry decode(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        if (yaml.getInt("format", -1) != FORMAT) {
            throw new InvalidConfigurationException("unknown journal format " + yaml.get("format"));
        }
        String[] center = String.valueOf(yaml.getString("center", "0 0 0")).trim().split(" ");
        if (center.length != 3) throw new InvalidConfigurationException("invalid centre");
        ConfigurationSection tiles = yaml.getConfigurationSection("tiles");
        List<CapturedBlock> blocks = new ArrayList<>();
        List<String> lines = yaml.getStringList("blocks");
        for (int i = 0; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(" ", 5);
            if (parts.length != 5) throw new InvalidConfigurationException("invalid block line " + (i + 1));
            String data = parts[4];
            blocks.add(new CapturedBlock(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    materialOf(data), data, parts[3].equals("1"), null,
                    tiles == null ? null : TileSnapshot.read(tiles.getConfigurationSection(Integer.toString(i)))));
        }
        return new Entry(UUID.fromString(require(yaml, "id")), UUID.fromString(require(yaml, "world")),
                yaml.getString("world-name", "?"), Double.parseDouble(center[0]), Double.parseDouble(center[1]),
                Double.parseDouble(center[2]), yaml.getLong("created"), blocks);
    }

    /** {@code minecraft:oak_stairs[facing=east]} names {@code minecraft:oak_stairs}. */
    public static String materialOf(String data) {
        int bracket = data.indexOf('[');
        return bracket < 0 ? data : data.substring(0, bracket);
    }

    private Path file(UUID id) {
        return folder.resolve(id + SUFFIX);
    }

    private static String require(YamlConfiguration yaml, String key) throws InvalidConfigurationException {
        String value = yaml.getString(key);
        if (value == null || value.isBlank()) throw new InvalidConfigurationException("missing " + key);
        return value;
    }

    private void syncFolder() {
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
            // Not every platform can flush a directory; the rename is still atomic.
        }
    }
}
