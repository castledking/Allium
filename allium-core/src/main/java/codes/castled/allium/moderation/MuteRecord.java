package codes.castled.allium.moderation;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable persisted mute entry for a player. When {@code expiresAt} is {@code null}, the mute is
 * permanent until removed with {@code /unmute}.
 */
public record MuteRecord(
        UUID playerUuid,
        String playerName,
        String actorName,
        String reason,
        long createdAt,
        Long expiresAt
) {

    public static final String DEFAULT_REASON = "No reason specified.";
    public static final int MAX_REASON_LENGTH = 256;

    public MuteRecord {
        Objects.requireNonNull(playerUuid, "playerUuid");
        playerName = (playerName == null || playerName.isBlank()) ? playerUuid.toString() : playerName.trim();
        actorName = (actorName == null || actorName.isBlank()) ? "Console" : actorName.trim();
        reason = normalizeReason(reason);
    }

    public static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_REASON;
        }
        String trimmed = raw.trim();
        return trimmed.length() <= MAX_REASON_LENGTH ? trimmed : trimmed.substring(0, MAX_REASON_LENGTH);
    }

    public boolean isPermanent() {
        return expiresAt == null;
    }

    public boolean isExpired(long nowMillis) {
        return expiresAt != null && nowMillis >= expiresAt;
    }

    public long remainingMillis(long nowMillis) {
        if (expiresAt == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, expiresAt - nowMillis);
    }

    public String formattedRemaining(long nowMillis) {
        if (expiresAt == null) {
            return "permanent";
        }
        return DurationParser.formatRemaining(remainingMillis(nowMillis));
    }
}
