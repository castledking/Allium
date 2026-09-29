package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.harvest.HarvestBranding;
import org.bukkit.NamespacedKey;

/** PersistentDataContainer keys used by the kitchen. */
final class KitchenKeys {

    /** Marks an entity as a placed pie; the value is "display" or "hitbox". */
    static final NamespacedKey PIE_PART = key("pie_part");
    /** On a pie hitbox: the UUID of the display entity that holds the state. */
    static final NamespacedKey PIE_DISPLAY = key("pie_display");
    /** On a pie display: the UUID of its hitbox. */
    static final NamespacedKey PIE_HITBOX = key("pie_hitbox");

    static final NamespacedKey PIE_PHASE = key("pie_phase");
    static final NamespacedKey PIE_STEP = key("pie_step");
    static final NamespacedKey PIE_PROGRESS = key("pie_progress");
    static final NamespacedKey PIE_TYPE = key("pie_type");
    static final NamespacedKey PIE_REFUNDS = key("pie_refunds");
    static final NamespacedKey PIE_BAKED = key("pie_baked");
    static final NamespacedKey PIE_BITES = key("pie_bites");

    /** Epoch millis at which a baked pie turns cold. On pie displays and baked pie items. */
    static final NamespacedKey COOLS_AT = key("cools_at");

    /** On a stove's furniture entity: its three furnace slots, serialized. */
    static final NamespacedKey STOVE_SLOTS = key("stove_slots");
    /** On a stove's furniture entity: burn time, burn total, cook time, cook total. */
    static final NamespacedKey STOVE_PROGRESS = key("stove_progress");
    /** On a stove's furniture entity: experience earned but not yet collected. */
    static final NamespacedKey STOVE_EXPERIENCE = key("stove_experience");

    /** The number of items a storage bag holds. */
    static final NamespacedKey BAG_AMOUNT = key("bag_amount");

    /** Chunk PDC prefix for kneading progress, suffixed with the block position. */
    static final String KNEAD_PREFIX = "knead_";

    static NamespacedKey knead(int x, int y, int z) {
        return key(KNEAD_PREFIX + x + "_" + y + "_" + z);
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey(HarvestBranding.NAMESPACE, name.replace('-', '_'));
    }

    private KitchenKeys() {}
}
