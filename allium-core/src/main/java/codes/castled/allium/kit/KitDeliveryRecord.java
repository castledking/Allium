package codes.castled.allium.kit;

import java.util.Objects;
import java.util.UUID;

/**
 * An in-flight kit delivery row in {@code allium_kit_deliveries}.
 */
public record KitDeliveryRecord(
        UUID id,
        UUID playerUuid,
        String kitName,
        UUID claimToken,
        String claimOwner,
        String itemsPayload,
        int itemCount,
        long cooldownMillis,
        boolean oneTime,
        boolean bypassCooldown,
        long createdAt
) {
    public KitDeliveryRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerUuid, "playerUuid");
        kitName = KitNames.validate(kitName);
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(claimOwner, "claimOwner");
        Objects.requireNonNull(itemsPayload, "itemsPayload");
        if (itemCount < 1) {
            throw new IllegalArgumentException("itemCount must be >= 1");
        }
        if (cooldownMillis < 0) {
            throw new IllegalArgumentException("cooldownMillis must be >= 0");
        }
    }
}
