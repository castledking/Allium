package codes.castled.allium.moderation;

import codes.castled.allium.teleport.SavedLocation;
import java.util.UUID;

/** The original return location survives reassignment to another jail until release; null expiry means indefinite. */
public record JailAssignment(UUID playerUuid, String playerName, String actorName, String reason,
                             long createdAt, String jailName, SavedLocation returnLocation, Long expiresAt) {
    public JailAssignment {
        playerName = ModerationRepository.playerName(playerUuid, playerName);
        actorName = ModerationRepository.actorName(actorName);
        reason = MuteRecord.normalizeReason(reason);
        jailName = ModerationRepository.jailName(jailName);
        ModerationRepository.validateLocation(returnLocation);
    }

    public JailAssignment(UUID playerUuid, String playerName, String actorName, String reason,
                          long createdAt, String jailName, SavedLocation returnLocation) {
        this(playerUuid, playerName, actorName, reason, createdAt, jailName, returnLocation, null);
    }

    public boolean isPermanent() {
        return expiresAt == null;
    }

    public boolean isExpired(long nowMillis) {
        return expiresAt != null && nowMillis >= expiresAt;
    }

    public String formattedRemaining(long nowMillis) {
        if (expiresAt == null) {
            return "indefinite";
        }
        long remaining = Math.max(0L, expiresAt - nowMillis);
        return DurationParser.formatRemaining(remaining);
    }
}
