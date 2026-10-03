package codes.castled.allium.tradingcards.boost;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import codes.castled.allium.tradingcards.item.CardFrame;
import codes.castled.allium.tradingcards.item.TradingCardData;
import com.github.darksoulq.relique.event.RelicEquipEvent;
import com.github.darksoulq.relique.event.RelicUnequipEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Applies a card's boosts when it is equipped into the Relique card slot, and
 * removes them when it leaves.
 *
 * <p>Four API constraints are handled here, each of which would otherwise cause
 * a silent leak or a wrong reading:
 *
 * <ol>
 *   <li><b>{@code getEntity()} is never assumed non-null.</b> The unequip path
 *       resolves it through {@code Bukkit.getEntity}, which returns null for an
 *       offline entity, and posts the event anyway.
 *   <li><b>Relique's own modifiers are already gone.</b> It runs
 *       {@code removeModifiers} before posting, so by the time this runs there
 *       is no race with its bookkeeping — apply and remove are safe to do
 *       inline.
 *   <li><b>No equipping from inside an unequip handler.</b> It re-enters
 *       Relique's own equip path and interleaves its modifier bookkeeping.
 *   <li><b>Only the card slot is handled.</b> The other five Relique slots are
 *       not ours and must not trigger boost changes.
 * </ol>
 */
public class BoostListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BoostService boosts;
    private final EquippedCardTracker tracker;
    private final Supplier<String> equipMessage;

    /**
     * @param equipMessage the config's equip line, read per equip so a reload
     *                     reaches it; blank sends nothing
     */
    public BoostListener(BoostService boosts, EquippedCardTracker tracker,
                         Supplier<String> equipMessage) {
        this.boosts = boosts;
        this.tracker = tracker;
        this.equipMessage = equipMessage;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquip(RelicEquipEvent event) {
        var entity = event.getEntity();
        if (!(entity instanceof Player player)) return;
        if (!TradingCardsBranding.RELIQUE_SLOT.equals(event.getSlotId())) return;

        var card = TradingCardData.read(event.getItem());
        if (card.isEmpty()) {
            // A non-card in the slot: the validator should have refused it, but
            // if one got in, make sure nothing is left applied.
            boosts.remove(player);
            tracker.clear(player.getUniqueId());
            return;
        }
        List<String> granted = boosts.apply(player, card.get());
        tracker.set(player.getUniqueId(), card.get(), granted);
        announce(player, event.getItem(), card.get());
    }

    /**
     * Tells the owner the card went in.
     *
     * <p>Only on a real equip: Relique posts this event from {@code equip} alone,
     * not from the xp write-back's {@code updateItem} or from re-syncing modifiers
     * on join, so a levelling card does not repeat it.
     */
    private void announce(Player player, ItemStack item, TradingCardData card) {
        String template = equipMessage == null ? null : equipMessage.get();
        if (template == null || template.isBlank()) {
            return;
        }
        Component title = CardFrame.title(item.lore());
        if (title == null) {
            String mob = card.mob() == null ? "" : card.mob().toLowerCase(Locale.ROOT).replace('_', ' ');
            title = Component.text(mob.isEmpty() ? "Trading Card"
                : Character.toUpperCase(mob.charAt(0)) + mob.substring(1) + " Trading Card");
        }
        player.sendMessage(MM.deserialize(template,
            Placeholder.component("card", title),
            Placeholder.unparsed("tier", card.tier().name().toLowerCase(Locale.ROOT)),
            Placeholder.unparsed("level", Integer.toString(card.level()))));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnequip(RelicUnequipEvent event) {
        var entity = event.getEntity();
        if (!(entity instanceof Player player)) return;
        if (!TradingCardsBranding.RELIQUE_SLOT.equals(event.getSlotId())) return;
        // Unconditional: the slot-limit-overflow path and the death path both
        // post this event, and in each case whatever was applied must go.
        boosts.remove(player);
        tracker.clear(player.getUniqueId());
    }
}
