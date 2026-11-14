package codes.castled.allium.moderation;

import java.util.UUID;

/** Immutable warning or staff-note history; IDs are local to their respective table. */
public record HistoryEntry(long id, UUID playerUuid, String targetName, String actorName, String reason, long createdAt) {
    public HistoryEntry {
        targetName = ModerationRepository.playerName(playerUuid, targetName);
        actorName = ModerationRepository.actorName(actorName);
        reason = MuteRecord.normalizeReason(reason);
    }
}
