package codes.castled.allium.tool.disarmer;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Persistent per-mob pickup block after a disarm, so a mob cannot immediately re-equip the
 * dropped pieces. Stored on the entity, so it survives chunk unloads and restarts without
 * changing the mob's vanilla pickup flag.
 */
public final class PickupSuppression {

    static final NamespacedKey BLOCKED_UNTIL = new NamespacedKey("allium", "disarmer_pickup_blocked_until");

    private final LongSupplier clock;

    PickupSuppression(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void block(Entity entity, long durationMillis) {
        long until = clock.getAsLong() + durationMillis;
        entity.getPersistentDataContainer().set(BLOCKED_UNTIL, PersistentDataType.LONG, until < 0L ? Long.MAX_VALUE : until);
    }

    /** @return true when the entity is still blocked; an expired block is removed. */
    public boolean isBlocked(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Long until;
        try {
            until = pdc.get(BLOCKED_UNTIL, PersistentDataType.LONG);
        } catch (IllegalArgumentException e) {
            pdc.remove(BLOCKED_UNTIL);
            return false;
        }
        if (until == null) {
            return false;
        }
        if (clock.getAsLong() >= until) {
            pdc.remove(BLOCKED_UNTIL);
            return false;
        }
        return true;
    }
}
