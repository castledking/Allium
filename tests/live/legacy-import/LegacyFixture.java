import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.Statement;
import java.util.Properties;

/**
 * Builds the gate's legacy database with a given H2 driver (args: jar, driver class, database base
 * path, SQL file). Legacy Allium's relocated H2 writes its own class names into the file, so the
 * Spigot lane builds its fixture through the legacy jar, exactly as legacy Allium would have.
 */
public final class LegacyFixture {
    public static void main(String[] args) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Driver driver = (Driver) Class.forName(args[1], true, loader).getDeclaredConstructor().newInstance();
            // As legacy Allium did: user sa, no password (H2 then requires that login to read it).
            Properties login = new Properties();
            login.setProperty("user", "sa");
            login.setProperty("password", "");
            try (Connection c = driver.connect("jdbc:h2:" + args[2] + ";MODE=MySQL", login); Statement s = c.createStatement()) {
                for (String sql : Files.readString(Path.of(args[3])).split(";\n")) {
                    if (!sql.isBlank()) s.execute(sql);
                }
            }
        }
    }
}
