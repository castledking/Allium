package codes.castled.allium.tradingcards.card;

import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Turns a mob kill into a card, when the mob is configured to drop one.
 *
 * <p>The card is added to the mob's drops, so it falls where the mob died.
 * Listens at HIGH rather than MONITOR because it changes the drop list, which
 * MONITOR handlers must not; it still never cancels or alters the death.
 *
 * <p>The killer must be a player. A card is an item, and items belong to
 * players; a card dropped by a dispenser or a farm would be unclaimable. A
 * mob killed by a player who has since logged out falls to the same rule and
 * is skipped.
 */
public class CardDropListener implements Listener {

    private final CardRegistry registry;
    private final CardRoller roller;
    private final TradingCardsConfig config;
    private final CardFactory factory;
    private final RandomGenerator random;
    private final java.util.function.Consumer<DropResult> onDrop;

    /** What a card drop produced, for logging and for the drop event. */
    public record DropResult(Player killer, CardDefinition definition, Tier tier,
                             int quality, QualityBand band, ItemStack card) {}

    public CardDropListener(CardRegistry registry, CardRoller roller,
                            TradingCardsConfig config, CardFactory factory,
                            RandomGenerator random,
                            java.util.function.Consumer<DropResult> onDrop) {
        this.registry = registry;
        this.roller = roller;
        this.config = config;
        this.factory = factory;
        this.random = random;
        this.onDrop = onDrop;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!registry.hasCards()) {
            return;
        }
        // EntityDeathEvent exposes the killer as a LivingEntity; a card is an
        // item, so only a player can own one. A dispenser or a farm produces no
        // card, and a mob killed by a player who has since logged out falls to
        // the same rule.
        if (!(dead.getKiller() instanceof Player killer)) {
            return;
        }
        Optional<CardRoller.RolledCard> rolled = roller.roll(dead.getType().name(), random);
        if (rolled.isEmpty()) {
            return;
        }
        CardRoller.RolledCard result = rolled.get();
        Optional<ItemStack> card = factory.create(result, config, random);
        if (card.isEmpty()) {
            return;
        }
        ItemStack stack = card.get();
        // Into the mob's own drops, so the card falls where it died like the
        // rest of its loot, and a looting or drop-modifying plugin sees it too.
        event.getDrops().add(stack);
        onDrop.accept(new DropResult(killer, result.definition(), result.tier(),
            result.quality(), result.band(), stack));
    }

    /**
     * Cards for a mob, grouped by config id, for the inspect command.
     */
    public Map<String, List<Tier>> summary() {
        Map<String, List<Tier>> out = new LinkedHashMap<>();
        for (var entry : registry.byId().entrySet()) {
            out.put(entry.getKey(), entry.getValue().droppableTiers());
        }
        return out;
    }

    /** Normalises a mob name the way the registry keys its cards. */
    public static String mobKey(String mobType) {
        return mobType == null ? "" : mobType.trim().toLowerCase(Locale.ROOT);
    }

    /** Every mob that currently has a card configured. */
    public List<String> mobs() {
        List<String> out = new ArrayList<>();
        for (CardDefinition definition : registry.all()) {
            out.add(definition.mob());
        }
        return out;
    }
}
