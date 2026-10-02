package codes.castled.allium.tradingcards.morph;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.plugin.Plugin;

/**
 * Makes a morphed player behave like the mob they are pretending to be.
 *
 * <p>All of this is ours, because the disguise is cosmetic. LibsDisguises only
 * changes packets, so a player wearing a creeper is not a creeper to anything
 * that reads the server-side entity — including mob AI.
 *
 * <h2>The targeting rule</h2>
 *
 * <p>A morphed player shaped like a hostile is not a target until they strike
 * first. Before that, targeting is cancelled; after it, the mobs that witnessed
 * the attack converge, and mobs that spawn later do not, because they have no
 * memory of an event they never saw.
 *
 * <p>That asymmetry is the whole reason it reads as vanilla rather than as an
 * invincibility button. A mob that watched you punch a cow comes for you. A
 * zombie that wandered in from the next field has no idea what happened.
 *
 * <p>Two mobs are exempt entirely: named vindicators and evokers, because
 * Johnny is a vanilla pacifist and a morph should not start a fight its wearer
 * did not ask for.
 *
 * <h2>Why the cancellation is ours and not LibsDisguises'</h2>
 *
 * <p>LibsDisguises cancels {@code EntityTargetEvent} for <i>disguised</i>
 * entities by default, which is right for a cosmetic plugin and exactly wrong
 * here — it would make a morphed player invisible to mob AI, so the morph would
 * have no effect on anything at all. The disguise is created with that
 * cancellation suppressed, and this listener does the targeting instead.
 */
public class MorphTargetingListener implements Listener {

    private final MorphService morphs;

    public MorphTargetingListener(MorphService morphs) {
        this.morphs = morphs;
    }

    /**
     * A mob choosing a target.
     *
     * <p>Only the hostile-form case is refused. A morphed cow is not something a
     * wolf objects to, and cancelling that would make every animal form a
     * free pass.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        // getEntity() is typed as Entity on this event, so the cast is explicit
        // rather than a pattern match.
        if (!(event.getEntity() instanceof LivingEntity mob)) {
            return;
        }
        if (!(event.getTarget() instanceof Player target)) {
            return;
        }
        if (!morphs.isMorphed(target)) {
            return;
        }
        if (morphs.mayTarget(target, mob)) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * A morphed player attacking something.
     *
     * <p>This is what opens the mob's aggro. Recorded here rather than on the
     * next targeting event, because the mob that was hit is the one that saw it —
     * and only that mob, and any mob already targeting the player.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!(event.getEntity() instanceof org.bukkit.entity.LivingEntity victim)) {
            return;
        }
        morphs.recordAttack(attacker, victim);
    }
}
