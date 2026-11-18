package codes.castled.allium.importer.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipFile;

/**
 * Opens a legacy Allium H2 database without changing it. The H2 driver is never bundled (it would
 * push the jar past Hangar's 5 MB limit): it is loaded, in a class loader of its own, from a jar the
 * operator places in {@code plugins/Allium/import/}. That must be the H2 that wrote the file: H2
 * stores its own class names in the file, and legacy Allium's shaded copy renamed them to
 * {@code codes.castled.allium.libs.h2}, so a legacy database needs the legacy Allium jar itself; a
 * plain {@code h2} jar only reads files a plain H2 wrote. The database is copied first; only the copy is opened, read-only, so no H2 version can
 * upgrade or lock the original.
 */
public final class LegacyH2Source implements AutoCloseable {

    /** Driver classes, in order: a plain H2 jar, then legacy Allium's relocated copy. */
    static final List<String> DRIVERS = List.of("org.h2.Driver", "codes.castled.allium.libs.h2.Driver");

    private final URLClassLoader loader;
    private final Driver driver;
    private final Path jar;
    private final String driverClass;

    private LegacyH2Source(URLClassLoader loader, Driver driver, Path jar, String driverClass) {
        this.loader = loader;
        this.driver = driver;
        this.jar = jar;
        this.driverClass = driverClass;
    }

    public Path jar() {
        return jar;
    }

    public String driverClass() {
        return driverClass;
    }

    /** The first of {@code jars} (in the given order) that carries an H2 driver, or empty. */
    public static Optional<LegacyH2Source> load(List<Path> jars) throws IOException {
        for (Path jar : jars) {
            String found = null;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (String name : DRIVERS) {
                    if (zip.getEntry(name.replace('.', '/') + ".class") != null) {
                        found = name;
                        break;
                    }
                }
            } catch (IOException notAJar) {
                continue;
            }
            if (found == null) continue;
            URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            try {
                Driver driver = (Driver) Class.forName(found, true, loader).getDeclaredConstructor().newInstance();
                return Optional.of(new LegacyH2Source(loader, driver, jar, found));
            } catch (ReflectiveOperationException | LinkageError | ClassCastException failed) {
                loader.close();
                throw new IOException("could not load " + found + " from " + jar.getFileName() + ": " + failed, failed);
            }
        }
        return Optional.empty();
    }

    /** Legacy Allium opened its database as {@code sa} with no password (Database.java, HikariConfig). */
    public static final String LEGACY_USER = "sa";

    public Connection openReadOnly(Path copy) throws SQLException {
        return openReadOnly(copy, LEGACY_USER, "");
    }

    /**
     * Opens {@code copy} (a {@code .mv.db} file) read-only as {@code user}; the caller closes the
     * connection. H2 keeps the user that created a database, so the login must match it.
     */
    public Connection openReadOnly(Path copy, String user, String password) throws SQLException {
        String file = copy.toAbsolutePath().toString();
        if (!file.endsWith(".mv.db")) throw new SQLException("not an H2 .mv.db file: " + copy.getFileName());
        String base = file.substring(0, file.length() - ".mv.db".length()).replace('\\', '/');
        Properties properties = new Properties();
        properties.setProperty("user", user == null ? "" : user);
        properties.setProperty("password", password == null ? "" : password);
        Connection c;
        try {
            c = driver.connect("jdbc:h2:" + base + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE;MODE=MySQL", properties);
        } catch (SQLException failed) {
            // H2 writes its own class names into the file, and legacy Allium's shaded H2 renamed them
            // (codes.castled.allium.libs.h2), so only the same build reads it. The shaded jar also
            // drops H2's message texts, leaving "(Message 90030 not found)".
            if (failed.getErrorCode() == 28000) {
                throw new SQLException("the legacy database refused user '" + user + "' (error 28000). Legacy Allium used 'sa' "
                        + "with no password; set user and password in import/legacy-allium.yml if yours differs",
                        failed.getSQLState(), 28000, failed);
            }
            if (failed.getErrorCode() == 90030) {
                throw new SQLException("H2 from " + jar.getFileName() + " cannot read this database (error 90030). H2 stores its own "
                        + "class names in the file, so it needs the H2 build that wrote it: for a legacy Allium database, the legacy "
                        + "Allium jar (its relocated H2), not a plain h2 jar", failed.getSQLState(), 90030, failed);
            }
            throw failed;
        }
        if (c == null) throw new SQLException("the H2 driver refused the URL");
        return c;
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }

    /** A source file copied for one run: the copy's path and the source's SHA-256. */
    public record Snapshot(Path copy, String sha256, long bytes) {
    }

    /**
     * Copies {@code source} to {@code copy} and hashes it. Refuses while another process holds the
     * database open (legacy Allium still running), since a copy could then be inconsistent.
     */
    public static Snapshot snapshot(Path source, Path copy) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("no legacy database at " + source);
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            FileLock lock;
            try {
                lock = channel.tryLock(0, Long.MAX_VALUE, true);
            } catch (OverlappingFileLockException held) {
                lock = null;
            }
            if (lock == null) throw new IOException("the legacy database is open in another process; stop that server first");
            try {
                Files.createDirectories(copy.getParent());
                Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                lock.release();
            }
        }
        return new Snapshot(copy, sha256(copy), Files.size(copy));
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            MessageDigest digest = ((DigestInputStream) in).getMessageDigest();
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
