package codes.castled.allium.tool.disarmer;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Converts pre-recode Mob Disarmer metadata into {@link DisarmerCharges}.
 *
 * <p>The pre-recode tool stored {@code allium:mob_disarmer_charges} (int, 0..3),
 * {@code allium:mob_disarmer_recharge_at} (epoch millis), {@code allium:mob_disarmer_broken} and
 * {@code allium:mob_disarmer_uses}, and identified itself through {@code allium:custom_item_id}
 * or {@code nexo:id} set to one of {@link #VARIANT_IDS}. A missing charge value meant a full tool.
 * Imported values are preserved exactly, clamped to the configured maximum; a depleted tool keeps
 * its recharge timestamp and a broken tool stays broken.
 */
public final class LegacyDisarmerImport {

    /** Indexed by remaining charges, as the pre-recode tool wrote them. */
    public static final List<String> VARIANT_IDS = List.of(
            "mob_disarmer_0-3",
            "mob_disarmer_1-3",
            "mob_disarmer_2-3",
            "mob_disarmer"
    );

    private LegacyDisarmerImport() {
    }

    public static boolean isLegacyVariant(@Nullable String id) {
        return id != null && VARIANT_IDS.contains(id.trim());
    }

    public static DisarmerCharges importCharges(
            @Nullable Integer charges,
            @Nullable Long rechargeAt,
            boolean broken,
            @Nullable Long uses,
            int maxCharges
    ) {
        if (maxCharges < 1) {
            throw new IllegalArgumentException("maxCharges must be >= 1; got " + maxCharges);
        }
        long safeUses = uses == null ? 0L : Math.max(0L, uses);
        if (broken) {
            return new DisarmerCharges(0, 0L, true, safeUses);
        }
        int imported = charges == null ? maxCharges : Math.max(0, Math.min(charges, maxCharges));
        long readyAt = imported > 0 || rechargeAt == null ? 0L : Math.max(0L, rechargeAt);
        return new DisarmerCharges(imported, readyAt, false, safeUses);
    }
}
