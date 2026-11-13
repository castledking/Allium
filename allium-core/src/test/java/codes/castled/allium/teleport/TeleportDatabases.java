package codes.castled.allium.teleport;

import codes.castled.allium.storage.ConnectionSource;
import codes.castled.allium.storage.SchemaMigrator;
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
import java.util.Map;
import java.util.UUID;

/**
 * Real JDBC databases. SQL identifiers are redirected to fresh random tables, including schema
 * metadata, so optional MySQL tests never delete another component's tables, rows or version.
 */
final class TeleportDatabases {

    static final class Db implements AutoCloseable {
        private final String name;
        private final SqlDialect dialect;
        private final ConnectionSource physical;
        private final Map<String, String> tables;

        Db(String name, SqlDialect dialect, ConnectionSource physical) {
            this.name = name;
            this.dialect = dialect;
            this.physical = physical;
            String prefix = "allium_tp_test_" + UUID.randomUUID().toString().replace("-", "") + "_";
            tables = Map.of(TeleportRepository.LOCATIONS_TABLE, prefix + "locations",
                    TeleportRepository.COOLDOWNS_TABLE, prefix + "cooldowns", SchemaMigrator.TABLE, prefix + "schema");
        }

        String name() {
            return name;
        }

        SqlDialect dialect() {
            return dialect;
        }

        ConnectionSource source() {
            return () -> {
                Connection connection = physical.connection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(connection, method, rewriteArguments(args));
                            if (method.getName().equals("createStatement")) {
                                Statement statement = (Statement) result;
                                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                                        (p, m, a) -> invoke(statement, m, rewriteArguments(a)));
                            }
                            return result;
                        });
            };
        }

        private Object[] rewriteArguments(Object[] args) {
            if (args == null || args.length == 0 || !(args[0] instanceof String sql)) return args;
            Object[] copy = args.clone();
            for (var table : tables.entrySet()) sql = sql.replace(table.getKey(), table.getValue());
            copy[0] = sql;
            return copy;
        }

        @Override
        public void close() throws SQLException {
            try (Connection connection = physical.connection(); Statement statement = connection.createStatement()) {
                // Only the three fresh random test tables are ever dropped.
                for (String table : tables.values()) statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private TeleportDatabases() {
    }

    static List<Db> all(Path directory) {
        List<Db> databases = new ArrayList<>();
        String sqliteUrl = "jdbc:sqlite:" + directory.resolve("teleports-" + UUID.randomUUID() + ".db").toAbsolutePath();
        databases.add(new Db("sqlite", SqlDialect.SQLITE, () -> DriverManager.getConnection(sqliteUrl)));
        String mysqlUrl = System.getenv("ALLIUM_TEST_MYSQL_URL");
        if (mysqlUrl != null && !mysqlUrl.isBlank()) {
            databases.add(new Db("mysql", SqlDialect.MYSQL, () -> DriverManager.getConnection(mysqlUrl)));
        }
        return databases;
    }

    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
