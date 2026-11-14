package codes.castled.allium.info;

import java.util.Objects;
import java.util.UUID;

/** Persisted per-UUID join and last-seen metadata for {@code /seen} and {@code /whois}. */
public record PlayerInfoRecord(
        UUID uuid,
        String lastName,
        long firstJoin,
        long lastJoin,
        long lastSeen
) {
    public PlayerInfoRecord {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(lastName, "lastName");
        if (lastName.isBlank()) {
            throw new IllegalArgumentException("lastName must not be blank");
        }
    }
}
