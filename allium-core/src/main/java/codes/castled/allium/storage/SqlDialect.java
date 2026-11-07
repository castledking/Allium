package codes.castled.allium.storage;

import java.util.Locale;

/** The SQL databases Allium stores data in. */
public enum SqlDialect {
    SQLITE,
    MYSQL;

    public static SqlDialect fromConfig(String raw) {
        return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
            case "MYSQL", "MARIADB" -> MYSQL;
            case "SQLITE" -> SQLITE;
            default -> throw new IllegalArgumentException("database.type must be SQLITE or MYSQL, not '" + raw + "'");
        };
    }
}
