package codes.castled.allium.moderation;

import java.util.UUID;

/** Persisted freeze state, including the staff member and reason. */
public record FreezeRecord(UUID playerUuid, String playerName, String actorName, String reason, long createdAt) {
    public FreezeRecord {
        playerName = ModerationRepository.playerName(playerUuid, playerName);
        actorName = ModerationRepository.actorName(actorName);
        reason = MuteRecord.normalizeReason(reason);
    }
}
