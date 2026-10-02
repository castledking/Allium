package codes.castled.allium.tradingcards.boost;

import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * Strips a hostile mob's weapon on a landed melee hit, at the rate the
 * attacker's equipped card grants.
 *
 * <p>Reads the live boost total rather than caching it, so unequipping mid-fight
 * takes effect immediately and re-equipping needs no re-registration.
 *
 * <p>The weapon is cleared, not dropped and not destroyed: a mob that loses its
 * sword to this should simply be bare-handed, and a player collecting the
 * difference is a side effect nobody asked for.
 */
public class DisarmListener implements Listener {

    /** What the boost actually reads: this player's total disarm rate. */
    @FunctionalInterface
    public interface DisarmRate {
        double rateFor(UUID player);
    }

    private final DisarmRate rates;
    private final DoubleSupplier chance;

    public DisarmListener(DisarmRate rates, DoubleSupplier chance) {
        this.rates = rates;
        this.chance = chance;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (!victim.isValid()) {
            return;
        }
        double rate = rates.rateFor(attacker.getUniqueId());
        if (rate <= 0.0) {
            return;
        }
        // Clamped, because a card rolling a rate above 1 is a config mistake and
        // an unclamped roll would be a guaranteed disarm rather than a boost.
        double probability = Math.min(1.0, rate);
        if (Math.random() >= probability) {
            return;
        }
        EntityEquipment equipment = victim.getEquipment();
        if (equipment == null) {
            return;
        }
        ItemStack weapon = equipment.getItemInMainHand();
        if (weapon == null || weapon.getType().isAir()) {
            // Nothing to disarm. Silently doing nothing is correct here: most mob
            // types never hold anything, and a message per hit would be noise.
            return;
        }
        equipment.setItemInMainHand(null);
    }
}
