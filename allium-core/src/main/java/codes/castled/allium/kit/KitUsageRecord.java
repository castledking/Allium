package codes.castled.allium.kit;

import codes.castled.allium.travel.TravelNames;

import java.util.Objects;
import java.util.UUID;

/**
 * Persisted per-player usage row for a kit in {@code allium_kit_usages}.
 */
public record KitUsageRecord(
        UUID playerUuid,
        String kitName,
        long lastClaimedAt,
        long claimCount,
        boolean oneTimeClaimed
) {
    public KitUsageRecord {
        Objects.requireNonNull(playerUuid, "playerUuid");
        kitName = TravelNames.normalize(kitName);
    }
}
