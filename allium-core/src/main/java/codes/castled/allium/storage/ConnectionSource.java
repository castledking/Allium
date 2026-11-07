package codes.castled.allium.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Hands out pooled JDBC connections; callers close them. */
@FunctionalInterface
public interface ConnectionSource {
    Connection connection() throws SQLException;
}
