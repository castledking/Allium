package codes.castled.allium.moderation;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SqlDialect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real JDBC databases with random tables: never drops shared application tables on MySQL. */
final class ModerationDatabases {
    static final List<String> S19_TABLES = List.of(ModerationRepository.BANS_TABLE, ModerationRepository.FREEZES_TABLE,
            ModerationRepository.JAILS_TABLE, ModerationRepository.JAILED_TABLE,
            ModerationRepository.WARNINGS_TABLE, ModerationRepository.NOTES_TABLE);

    record Db(String name, SqlDialect dialect, ConnectionSource source) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            List<String> tables = new ArrayList<>(S19_TABLES);
            tables.addAll(List.of(ModerationRepository.VANISH_TABLE, ModerationRepository.MUTES_TABLE,
                    ModerationRepository.AUDIT_TABLE, "allium_schema"));
            try (Connection c = source.connection(); Statement s = c.createStatement()) {
                for (String table : tables) s.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private ModerationDatabases() {}

    static List<Db> all(Path dir) {
        List<Db> out = new ArrayList<>();
        out.add(database("sqlite", SqlDialect.SQLITE,
                "jdbc:sqlite:" + dir.resolve(UUID.randomUUID() + ".db").toAbsolutePath()));
        String url = System.getenv("ALLIUM_TEST_MYSQL_URL");
        if (url != null && !url.isBlank()) out.add(database("mysql", SqlDialect.MYSQL, url));
        return out;
    }

    private static Db database(String name, SqlDialect dialect, String url) {
        // Keep the prefixed index names below MySQL's 64-character identifier limit.
        String prefix = "at" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";
        ConnectionSource source = () -> {
            Connection actual = DriverManager.getConnection(url);
            if (dialect == SqlDialect.SQLITE) {
                try (Statement s = actual.createStatement()) { s.execute("PRAGMA busy_timeout = 10000"); }
            }
            return (Connection) Proxy.newProxyInstance(ModerationDatabases.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object value = invoke(method, actual,
                                method.getName().equals("prepareStatement") ? rewrite(args, prefix) : args);
                        if (method.getName().equals("getMetaData")) return metadata((DatabaseMetaData) value, prefix);
                        if (!method.getName().equals("createStatement")) return value;
                        return Proxy.newProxyInstance(ModerationDatabases.class.getClassLoader(), new Class<?>[]{Statement.class},
                                (p, m, a) -> invoke(m, value, rewrite(a, prefix)));
                    });
        };
        return new Db(name, dialect, source);
    }

    private static Object metadata(DatabaseMetaData actual, String prefix) {
        return Proxy.newProxyInstance(ModerationDatabases.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getIndexInfo")) return invoke(method, actual, args);
                    Object[] rewritten = args.clone();
                    rewritten[2] = ((String) rewritten[2]).replace("allium_", prefix);
                    ResultSet rows = (ResultSet) invoke(method, actual, rewritten);
                    return Proxy.newProxyInstance(ModerationDatabases.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                            (p, m, a) -> {
                                Object value = invoke(m, rows, a);
                                return m.getName().equals("getString") && value instanceof String text
                                        ? text.replace(prefix, "allium_") : value;
                            });
                });
    }

    private static Object[] rewrite(Object[] args, String prefix) {
        if (args == null || args.length == 0 || !(args[0] instanceof String sql)) return args;
        Object[] out = args.clone();
        out[0] = sql.replace("allium_", prefix);
        return out;
    }

    static Object invoke(Method method, Object receiver, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
