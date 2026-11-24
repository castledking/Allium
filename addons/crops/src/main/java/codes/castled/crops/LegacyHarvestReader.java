package codes.castled.crops;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipFile;

/**
 * Read-only reader for legacy Allium Harvest v1/v2 databases ({@code harvest.mv.db} H2 or
 * {@code harvest.db} SQLite). Always copies the database file before opening the copy read-only so
 * the original source database is never mutated or locked.
 */
final class LegacyHarvestReader {

    private static final List<String> H2_DRIVERS = List.of(
            "codes.castled.allium.libs.h2.Driver",
            "org.h2.Driver"
    );

    record LegacyCrop(
            UUID instanceUuid,
            String cropId,
            String pathId,
            UUID worldUuid,
            int x,
            int y,
            int z,
            int stage,
            String state,
            long plantedAt,
            long stageStartedAt,
            @Nullable Long nextGrowthAt,
            @Nullable Long remainingMs,
            @Nullable UUID planterUuid,
            @Nullable UUID visualUuid,
            @Nullable String fertilizerId,
            @Nullable Double speedMultiplier
    ) {}

    record LegacyCell(
            UUID instanceUuid,
            UUID worldUuid,
            int x,
            int y,
            int z
    ) {}

    record LegacySoil(
            UUID worldUuid,
            int x,
            int y,
            int z,
            long establishedAt,
            long exhaustedAt,
            @Nullable String fertilizerId
    ) {}

    record LegacySprinkler(
            UUID worldUuid,
            int x,
            int y,
            int z,
            String sprinklerId,
            @Nullable UUID visualUuid,
            long placedAt,
            @Nullable UUID ownerUuid
    ) {}

    record Snapshot(
            String sourceSha256,
            String tablePrefix,
            List<LegacyCrop> crops,
            List<LegacyCell> cells,
            List<LegacySoil> soils,
            List<LegacySprinkler> sprinklers,
            List<String> notes
    ) {
        Snapshot {
            crops = List.copyOf(crops);
            cells = List.copyOf(cells);
            soils = List.copyOf(soils);
            sprinklers = List.copyOf(sprinklers);
            notes = List.copyOf(notes);
        }
    }

    record DiscoveredSource(Path databaseFile, String tablePrefix) {}

    private LegacyHarvestReader() {}

    /**
     * Locates legacy Allium's harvest database in precedence order:
     * active {@code plugins/Allium/harvest/harvest.{mv.db,db}}, then newest tidy archive
     * {@code plugins/Allium/import/backups/tidy-*\/harvest/harvest.{mv.db,db}}.
     */
    static Optional<DiscoveredSource> discover(Path alliumDataFolder) throws IOException {
        if (alliumDataFolder == null) return Optional.empty();
        List<Path> harvestDirs = new ArrayList<>();
        Path activeDir = alliumDataFolder.resolve("harvest");
        if (Files.isDirectory(activeDir, LinkOption.NOFOLLOW_LINKS)) {
            harvestDirs.add(activeDir);
        }
        Path backups = alliumDataFolder.resolve("import").resolve("backups");
        if (Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS)) {
            try (var stream = Files.list(backups)) {
                stream.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                                && p.getFileName().toString().startsWith("tidy-"))
                        .sorted(Comparator.reverseOrder())
                        .map(p -> p.resolve("harvest"))
                        .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                        .forEach(harvestDirs::add);
            }
        }
        for (Path dir : harvestDirs) {
            for (String candidate : List.of("harvest.mv.db", "harvest.db")) {
                Path db = dir.resolve(candidate);
                if (Files.isRegularFile(db, LinkOption.NOFOLLOW_LINKS)) {
                    return Optional.of(new DiscoveredSource(db, readTablePrefix(dir.resolve("config.yml"))));
                }
            }
        }
        return Optional.empty();
    }

    static String readTablePrefix(Path configFile) {
        if (configFile == null || !Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) return "";
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(configFile.toFile());
            String raw = yaml.getString("storage.table-prefix", "");
            if (raw == null || raw.isBlank()) return "";
            String trimmed = raw.trim();
            if (!trimmed.matches("[A-Za-z0-9_]{0,32}")) {
                throw new IllegalArgumentException("Invalid legacy harvest storage.table-prefix: " + trimmed);
            }
            return trimmed;
        } catch (IllegalArgumentException bad) {
            throw bad;
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Copies {@code source} to {@code copy}, hashes it, and reads the copy read-only. */
    static Snapshot copyAndRead(Path source, Path copy, String tablePrefix, Path h2DriverDir) throws IOException, SQLException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("No legacy harvest database at " + source);
        }
        String sha256 = copyAndHash(source, copy);
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mv.db")) {
            return readH2Copy(copy, sha256, tablePrefix, h2DriverDir);
        }
        if (name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3")) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copy.toAbsolutePath())) {
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA query_only = ON");
                } catch (SQLException ignored) {
                }
                return read(c, tablePrefix, sha256);
            }
        }
        throw new IOException("Unsupported legacy harvest database format: " + source.getFileName()
                + " (expected .mv.db or .db)");
    }

    private static Snapshot readH2Copy(Path copy, String sha256, String tablePrefix, @Nullable Path h2DriverDir)
            throws IOException, SQLException {
        List<Path> jars = new ArrayList<>();
        if (h2DriverDir != null && Files.isDirectory(h2DriverDir)) {
            try (var stream = Files.list(h2DriverDir)) {
                stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted()
                        .forEach(jars::add);
            }
        }
        for (Path jar : jars) {
            String driverClass = findH2Driver(jar);
            if (driverClass == null) continue;
            try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Driver driver = (Driver) Class.forName(driverClass, true, loader).getDeclaredConstructor().newInstance();
                return openH2AndRead(driver, copy, sha256, tablePrefix);
            } catch (ReflectiveOperationException | LinkageError | ClassCastException failed) {
                throw new IOException("Could not load H2 driver " + driverClass + " from " + jar.getFileName() + ": " + failed.getMessage(), failed);
            }
        }
        // Fallback: try H2 on current classpath (useful in tests or when server supplies H2).
        for (String driverClass : H2_DRIVERS) {
            try {
                Driver driver = (Driver) Class.forName(driverClass).getDeclaredConstructor().newInstance();
                return openH2AndRead(driver, copy, sha256, tablePrefix);
            } catch (ReflectiveOperationException | LinkageError ignored) {
            }
        }
        throw new IOException("No H2 driver jar found in " + (h2DriverDir == null ? "plugins/Allium/import/" : h2DriverDir)
                + "; place the legacy Allium jar (which wrote harvest.mv.db) there to read H2 databases");
    }

    private static Snapshot openH2AndRead(Driver driver, Path copy, String sha256, String tablePrefix) throws SQLException {
        String file = copy.toAbsolutePath().toString();
        String base = file.substring(0, file.length() - ".mv.db".length()).replace('\\', '/');
        Properties props = new Properties();
        props.setProperty("user", "sa");
        props.setProperty("password", "");
        try (Connection c = driver.connect("jdbc:h2:" + base + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE;MODE=MySQL", props)) {
            if (c == null) throw new SQLException("H2 driver refused URL for " + copy.getFileName());
            return read(c, tablePrefix, sha256);
        }
    }

    @Nullable
    private static String findH2Driver(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String name : H2_DRIVERS) {
                if (zip.getEntry(name.replace('.', '/') + ".class") != null) return name;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static String copyAndHash(Path source, Path copy) throws IOException {
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            FileLock lock;
            try {
                lock = channel.tryLock(0, Long.MAX_VALUE, true);
            } catch (OverlappingFileLockException held) {
                lock = null;
            }
            if (lock == null) {
                throw new IOException("Legacy harvest database is open in another process; stop that server first");
            }
            try {
                if (copy.getParent() != null) Files.createDirectories(copy.getParent());
                Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                lock.release();
            }
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(copy), MessageDigest.getInstance("SHA-256"))) {
            MessageDigest md = ((DigestInputStream) in).getMessageDigest();
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Snapshot read(Connection c, String rawPrefix, String sourceSha256) throws SQLException {
        String prefix = rawPrefix == null ? "" : rawPrefix.trim();
        if (!prefix.matches("[A-Za-z0-9_]{0,32}")) {
            throw new IllegalArgumentException("Invalid table prefix: " + prefix);
        }
        List<LegacyCrop> crops = new ArrayList<>();
        List<LegacyCell> cells = new ArrayList<>();
        List<LegacySoil> soils = new ArrayList<>();
        List<LegacySprinkler> sprinklers = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        String cropsTable = prefix + "harvest_crops";
        String cellsTable = prefix + "harvest_crop_cells";
        String soilTable = prefix + "harvest_soil";
        String sprinklersTable = prefix + "harvest_sprinklers";

        if (tableExists(c, cropsTable)) {
            boolean hasSpeed = columnExists(c, cropsTable, "speed_multiplier");
            String sql = "SELECT instance_uuid, crop_id, path_id, world_uuid, x, y, z, stage, state, "
                    + "planted_at, stage_started_at, next_growth_at, remaining_ms, planter_uuid, visual_uuid, "
                    + "fertilizer_id" + (hasSpeed ? ", speed_multiplier" : "") + " FROM " + cropsTable;
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID instanceId = parseUuid(rs.getString("instance_uuid"));
                    UUID worldId = parseUuid(rs.getString("world_uuid"));
                    if (instanceId == null || worldId == null) {
                        notes.add("Skipped malformed " + cropsTable + " row (invalid instance_uuid or world_uuid)");
                        continue;
                    }
                    Double speed = null;
                    if (hasSpeed) {
                        double rawSpeed = rs.getDouble("speed_multiplier");
                        if (!rs.wasNull() && Double.isFinite(rawSpeed) && rawSpeed > 0.0d) speed = rawSpeed;
                    }
                    crops.add(new LegacyCrop(
                            instanceId,
                            nonNull(rs.getString("crop_id")),
                            nonNull(rs.getString("path_id")),
                            worldId,
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getInt("stage"),
                            nonNull(rs.getString("state")),
                            rs.getLong("planted_at"),
                            rs.getLong("stage_started_at"),
                            nullableLong(rs, "next_growth_at"),
                            nullableLong(rs, "remaining_ms"),
                            parseUuid(rs.getString("planter_uuid")),
                            parseUuid(rs.getString("visual_uuid")),
                            blankToNull(rs.getString("fertilizer_id")),
                            speed
                    ));
                }
            }
        } else {
            notes.add("Table '" + cropsTable + "' not present in source database");
        }

        if (tableExists(c, cellsTable)) {
            String sql = "SELECT instance_uuid, world_uuid, x, y, z FROM " + cellsTable;
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID instanceId = parseUuid(rs.getString("instance_uuid"));
                    UUID worldId = parseUuid(rs.getString("world_uuid"));
                    if (instanceId == null || worldId == null) {
                        notes.add("Skipped malformed " + cellsTable + " row (invalid UUID)");
                        continue;
                    }
                    cells.add(new LegacyCell(instanceId, worldId, rs.getInt("x"), rs.getInt("y"), rs.getInt("z")));
                }
            }
        }

        if (tableExists(c, soilTable)) {
            String sql = "SELECT world_uuid, x, y, z, established_at, exhausted_at, fertilizer_id FROM " + soilTable;
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID worldId = parseUuid(rs.getString("world_uuid"));
                    if (worldId == null) {
                        notes.add("Skipped malformed " + soilTable + " row (invalid world_uuid)");
                        continue;
                    }
                    soils.add(new LegacySoil(
                            worldId,
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getLong("established_at"),
                            rs.getLong("exhausted_at"),
                            blankToNull(rs.getString("fertilizer_id"))
                    ));
                }
            }
        } else {
            notes.add("Table '" + soilTable + "' not present (legacy v1 database or unused)");
        }

        if (tableExists(c, sprinklersTable)) {
            String sql = "SELECT world_uuid, x, y, z, sprinkler_id, visual_uuid, placed_at, owner_uuid FROM " + sprinklersTable;
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID worldId = parseUuid(rs.getString("world_uuid"));
                    if (worldId == null) {
                        notes.add("Skipped malformed " + sprinklersTable + " row (invalid world_uuid)");
                        continue;
                    }
                    sprinklers.add(new LegacySprinkler(
                            worldId,
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            nonNull(rs.getString("sprinkler_id")),
                            parseUuid(rs.getString("visual_uuid")),
                            rs.getLong("placed_at"),
                            parseUuid(rs.getString("owner_uuid"))
                    ));
                }
            }
        } else {
            notes.add("Table '" + sprinklersTable + "' not present (legacy v1 database or unused)");
        }

        return new Snapshot(sourceSha256, prefix, crops, cells, soils, sprinklers, notes);
    }

    private static boolean tableExists(Connection c, String tableName) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        for (String candidate : List.of(tableName, tableName.toUpperCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT))) {
            try (ResultSet rs = meta.getTables(null, null, candidate, new String[]{"TABLE"})) {
                if (rs.next()) return true;
            }
        }
        return false;
    }

    private static boolean columnExists(Connection c, String tableName, String columnName) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        for (String candidate : List.of(tableName, tableName.toUpperCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT))) {
            try (ResultSet rs = meta.getColumns(null, null, candidate, null)) {
                while (rs.next()) {
                    if (columnName.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    @Nullable
    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long val = rs.getLong(col);
        return rs.wasNull() ? null : val;
    }

    private static String nonNull(@Nullable String s) {
        return s == null ? "" : s.trim();
    }

    @Nullable
    private static String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
