package codes.castled.allium.moderation;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit record for staff vanish and mute moderation actions.
 */
public record ModerationAuditRecord(
        long id,
        long createdAt,
        String action,
        String actorName,
        UUID targetUuid,
        String targetName,
        String detail
) {

    public ModerationAuditRecord {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actorName, "actorName");
        detail = detail == null ? "" : detail;
    }
}
