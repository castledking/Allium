package codes.castled.allium.tradingcards.xp;

import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;

/** Vanilla catch XP and LiteFish's Bukkit event compatibility for EcoJobs. */
public final class FishingXpListener implements Listener {
    private final CardXpService xp;
    private final int liteFishCatchValue;
    private final boolean liteFishHandled;

    public FishingXpListener(CardXpService xp, int liteFishCatchValue, boolean liteFishHandled) {
        this.xp = xp;
        this.liteFishCatchValue = Math.max(0, liteFishCatchValue);
        this.liteFishHandled = liteFishHandled;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void prepareEcoJobsCatch(PlayerFishEvent event) {
        if (event.isCancelled() || event.getState() != PlayerFishEvent.State.CAUGHT_FISH
            || !(event.getCaught() instanceof Item item) || event.getExpToDrop() != 0
            || LiteFishBridge.speciesOf(item.getItemStack()) == null) return;
        // LiteFish constructs this event with zero XP. libreforge's catch_fish
        // uses expToDrop as its VALUE, so zero makes every EcoJobs counter zero.
        // Let the real trigger handle active jobs, conditions and rewards; do
        // not grant job XP directly or dispatch a duplicate fishing event.
        event.setExpToDrop(liteFishCatchValue);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCatch(PlayerFishEvent event) {
        if (event.isCancelled() || event.getState() != PlayerFishEvent.State.CAUGHT_FISH
            || !(event.getCaught() instanceof Item item)) return;
        String species = LiteFishBridge.speciesOf(item.getItemStack());
        // The LiteFish success event already awarded this drop. Its later
        // Bukkit event exists for other plugins and must not pay a second time.
        if (liteFishHandled && species != null) return;
        xp.award(event.getPlayer(), "fish-catch", 1.0, species, null);
    }
}
