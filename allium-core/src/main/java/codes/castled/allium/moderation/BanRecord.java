package codes.castled.allium.moderation;

import java.util.UUID;

/** A UUID ban, optionally also enforced against a literal address; null expiry means permanent. */
public record BanRecord(UUID playerUuid, String playerName, String actorName, String reason,
                        long createdAt, Long expiresAt, String ipAddress) {
    public BanRecord {
        playerName = ModerationRepository.playerName(playerUuid, playerName);
        actorName = ModerationRepository.actorName(actorName);
        reason = MuteRecord.normalizeReason(reason);
        ipAddress = ModerationRepository.ipAddress(ipAddress);
    }

    public boolean isPermanent() { return expiresAt == null; }

    public boolean isExpired(long nowMillis) { return expiresAt != null && nowMillis >= expiresAt; }
}
