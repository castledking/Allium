package codes.castled.allium.travel;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real JDBC databases; MySQL uses random table names and never drops shared application tables. */
final class TravelDatabases {
    record Db(String name, SqlDialect dialect, ConnectionSource source) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            try (Connection c = source.connection(); Statement s = c.createStatement()) {
                for (String table : List.of("allium_travel_homes", "allium_travel_warps",
                        "allium_travel_home_owners", "allium_travel_warp_names", "allium_schema")) {
                    s.execute("DROP TABLE IF EXISTS " + table);
                }
            }
        }
    }

    private TravelDatabases() {
    }

    static List<Db> all(Path dir) {
        List<Db> result = new ArrayList<>();
        result.add(sqlite(dir));
        String mysql = System.getenv("ALLIUM_TEST_MYSQL_URL");
        if (mysql != null && !mysql.isBlank()) result.add(database("mysql", SqlDialect.MYSQL, mysql));
        return result;
    }

    static Db sqlite(Path dir) {
        return database("sqlite", SqlDialect.SQLITE, "jdbc:sqlite:" + dir.resolve(UUID.randomUUID() + ".db").toAbsolutePath());
    }

    private static Db database(String name, SqlDialect dialect, String url) {
        String prefix = "allium_t" + UUID.randomUUID().toString().replace("-", "") + "_";
        ConnectionSource source = () -> {
            Connection actual = DriverManager.getConnection(url);
            if (dialect == SqlDialect.SQLITE) {
                try (Statement s = actual.createStatement()) {
                    s.execute("PRAGMA busy_timeout = 10000");
                }
            }
            return (Connection) Proxy.newProxyInstance(TravelDatabases.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        Object[] args = arguments;
                        if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                            args = args.clone();
                            args[0] = sql.replace("allium_", prefix);
                        }
                        Object value = invoke(method, actual, args);
                        if (!method.getName().equals("createStatement")) return value;
                        Statement statement = (Statement) value;
                        return Proxy.newProxyInstance(TravelDatabases.class.getClassLoader(), new Class<?>[]{Statement.class},
                                (statementProxy, statementMethod, statementArguments) -> {
                                    Object[] rewritten = statementArguments;
                                    if (rewritten != null && rewritten.length > 0 && rewritten[0] instanceof String sql) {
                                        rewritten = rewritten.clone();
                                        rewritten[0] = sql.replace("allium_", prefix);
                                    }
                                    return invoke(statementMethod, statement, rewritten);
                                });
                    });
        };
        return new Db(name, dialect, source);
    }

    static Object invoke(Method method, Object receiver, Object[] arguments) throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
