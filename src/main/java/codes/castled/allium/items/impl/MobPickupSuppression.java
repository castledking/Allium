package codes.castled.allium.items.impl;

import java.util.function.LongSupplier;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Persistent, per-mob cooldown used after the Mob Disarmer clears equipment. */
final class MobPickupSuppression {
    static final long DURATION_MS = 120_000L;

    private final NamespacedKey blockedUntilKey;
    private final LongSupplier clock;

    MobPickupSuppression(final Plugin plugin) {
        this(new NamespacedKey(plugin, "mob_disarmer_pickup_blocked_until"),
                System::currentTimeMillis);
    }

    MobPickupSuppression(final NamespacedKey blockedUntilKey, final LongSupplier clock) {
        this.blockedUntilKey = blockedUntilKey;
        this.clock = clock;
    }

    void block(final LivingEntity entity) {
        entity.getPersistentDataContainer().set(blockedUntilKey, PersistentDataType.LONG,
                clock.getAsLong() + DURATION_MS);
    }

    boolean cancelIfBlocked(final EntityPickupItemEvent event) {
        final PersistentDataContainer pdc = event.getEntity().getPersistentDataContainer();
        final Long blockedUntil = pdc.get(blockedUntilKey, PersistentDataType.LONG);
        if (blockedUntil == null) return false;
        if (clock.getAsLong() >= blockedUntil) {
            pdc.remove(blockedUntilKey);
            return false;
        }
        event.setCancelled(true);
        return true;
    }
}
