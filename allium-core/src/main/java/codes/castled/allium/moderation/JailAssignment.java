package codes.castled.allium.moderation;

import codes.castled.allium.teleport.SavedLocation;
import java.util.UUID;

/** The original return location survives reassignment to another jail until release. */
public record JailAssignment(UUID playerUuid, String playerName, String actorName, String reason,
                             long createdAt, String jailName, SavedLocation returnLocation) {
    public JailAssignment {
        playerName = ModerationRepository.playerName(playerUuid, playerName);
        actorName = ModerationRepository.actorName(actorName);
        reason = MuteRecord.normalizeReason(reason);
        jailName = ModerationRepository.jailName(jailName);
        ModerationRepository.validateLocation(returnLocation);
    }
}
