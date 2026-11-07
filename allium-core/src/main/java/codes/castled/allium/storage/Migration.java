package codes.castled.allium.storage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One schema step for a component. Statements run in order; each version is applied once and
 * recorded in {@code allium_schema}.
 */
public record Migration(int version, String description, Map<SqlDialect, List<String>> statements) {

    public Migration {
        if (version < 1) {
            throw new IllegalArgumentException("migration versions start at 1");
        }
        Objects.requireNonNull(description, "description");
        statements = Map.copyOf(statements);
        for (SqlDialect dialect : SqlDialect.values()) {
            if (!statements.containsKey(dialect)) {
                throw new IllegalArgumentException("migration " + version + " has no statements for " + dialect);
            }
        }
    }

    public static Migration of(int version, String description, List<String> sqlite, List<String> mysql) {
        return new Migration(version, description, Map.of(SqlDialect.SQLITE, List.copyOf(sqlite), SqlDialect.MYSQL, List.copyOf(mysql)));
    }
}
