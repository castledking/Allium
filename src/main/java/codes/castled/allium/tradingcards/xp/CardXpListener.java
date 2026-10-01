package codes.castled.allium.tradingcards.xp;

import codes.castled.allium.harvest.event.CropHarvestEvent;
import codes.castled.allium.tradingcards.TradingCardsModule;
import java.util.Locale;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

/**
 * The four vanilla and Allium-native xp sources.
 *
 * <p>All of them funnel through {@link CardXpService#award}, so the anti-farm
 * gates cannot be forgotten by the next source added. Every source here is
 * something a player does deliberately, which is why they are the first four:
 * the plugin-backed ones (quests, fishing, jobs) can wait without changing the
 * shape of anything.
 *
 * <p>Each listens at {@link EventPriority#MONITOR} and never cancels or
 * alters the event it observes. Card xp is an addition to what happened, not a
 * modification of it.
 */
public class CardXpListener implements Listener {

    private final TradingCardsModule module;
    private final CardXpService xp;

    public CardXpListener(TradingCardsModule module, CardXpService xp) {
        this.module = module;
        this.xp = xp;
    }

    /**
     * Killing a mob. The baseline source, and the one most likely to be farmed,
     * so it is also the one whose amount is worth revisiting first.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity().getKiller() instanceof Player player)) {
            return;
        }
        // A player killing another player is not mob xp. There is deliberately
        // no player-kill source at all: it is the easiest xp on the server to
        // farm with two accounts, and it turns a collectible into a reason to
        // start a fight.
        if (event.getEntity() instanceof Player) {
            return;
        }
        xp.award(player, "kill-mob", 1.0,
            event.getEntity().getType().name(), null);
    }

    /**
     * Breeding, on a per-mob-type cooldown.
     *
     * <p>Keyed per mob type because breeding a chicken should not put cows on
     * a cooldown, nor the reverse — and because "once per mob type" is the rule
     * that makes a mob farm unprofitable without making a player unable to
     * breed at all.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)) {
            return;
        }
        String mob = event.getEntity().getType().name();
        CardXpService.Award award = xp.award(player, "breed-mob", 1.0, mob, null);
        if (award.gated() && award.cooldownRemaining() > 0) {
            // Silent by design. A cooldown message on every failed breed would
            // spam a mob farm, and the player already knows they bred recently.
            return;
        }
    }

    /**
     * Harvesting an Allium crop.
     *
     * <p>Allium's own event, so custom crops pay out alongside vanilla ones
     * without a second listener or a config toggle.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCropHarvest(CropHarvestEvent event) {
        xp.award(event.getPlayer(), "farm-crop", 1.0, null, null);
    }

    /**
     * Completing an advancement, once each.
     *
     * <p>Without the once-each claim this is a repeatable xp fountain: an
     * advancement can be re-triggered by redoing its steps, and a player who
     * notices will redo them all.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Advancement advancement = event.getAdvancement();
        if (advancement == null) {
            return;
        }
        // The key is the namespace and path, not the display name, so a
        // resource pack rename cannot re-open a paid advancement.
        String key = advancement.getKey().toString();
        xp.award(event.getPlayer(), "advancement", 1.0, null, key);
    }

    /** Reads a mob name into the form the store keys on. */
    static String mobKey(String entityType) {
        return entityType == null ? "" : entityType.toLowerCase(Locale.ROOT);
    }

    public CardXpService service() {
        return xp;
    }
}
