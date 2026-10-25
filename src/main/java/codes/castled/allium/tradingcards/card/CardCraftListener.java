package codes.castled.allium.tradingcards.card;

import codes.castled.allium.PluginStart;
import codes.castled.allium.spawnercraft.MobHeadRegistry;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

/**
 * Crafting a card: eight heads of one mob around a sheet of paper.
 *
 * <p>Spawners are heads around a spawner core and plushies are wool around a
 * head, so a head now pays out in all three directions the same way: eight of
 * a mob around a plain centre. Paper as the centre produces a Simple card of
 * that mob whose quality is rolled like any drop's, a card a new player can
 * grind towards instead of owing to pure drop luck.
 *
 * <p>No real recipe backs the result, so like plushie crafting the result is
 * placed on prepare and handed out at the result click, one card per craft.
 * The matrix is re-validated on the click, so the only card that can ever come
 * out is one this listener computed for the grid the player is looking at.
 *
 * <p>The preview is the exact card the craft gives, quality included, so the
 * roll is held per player and mob until it is crafted. Rolling on every
 * prepare made the grid a free reroll: lift a head, put it back, and watch the
 * quality change until it read 100.
 */
public final class CardCraftListener implements Listener {

    private static final int CENTER = 4;
    private static final int[] SURROUNDING = {0, 1, 2, 3, 5, 6, 7, 8};

    /** The mob a head item is, or null when it is not craftable card stock. */
    @FunctionalInterface
    interface HeadType {
        EntityType mobOf(ItemStack item, boolean includeVanillaHeads);
    }

    private final TradingCardsModule module;
    /** Mirror of spawner crafting, so a vanilla skull can only make cards when it can make spawners. */
    private final boolean vanillaHeads;
    private final HeadType heads;
    /** Each player's next quality per mob, rolled on first sight and spent by the craft. */
    private final Map<UUID, Map<EntityType, Integer>> pendingQuality = new HashMap<>();

    public CardCraftListener(TradingCardsModule module) {
        this(module, MobHeadRegistry::getEntityType);
    }

    CardCraftListener(TradingCardsModule module, HeadType heads) {
        this.module = module;
        this.vanillaHeads = PluginStart.getInstance().getConfig()
            .getBoolean("spawnercraft.vanilla-heads-craft-spawners", false);
        this.heads = heads;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        ItemStack card = buildCard(player.getUniqueId(), headsAroundPaper(
            event.getInventory().getMatrix(), vanillaHeads, heads));
        if (card != null) {
            event.getInventory().setResult(card);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onResultClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof CraftingInventory inventory)) return;
        if (event.getSlotType() != InventoryType.SlotType.RESULT) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack shown = event.getCurrentItem();
        EntityType mob = headsAroundPaper(inventory.getMatrix(), vanillaHeads, heads);
        if (mob == null) return;
        if (TradingCardData.read(shown).filter(card -> isCardOf(card, mob.name())).isEmpty()) {
            return;
        }

        // No real recipe backs this result, so consume the grid and hand out
        // the card shown rather than re-rolling a fresh one on the click. The
        // held roll is spent, so the next card of this mob rolls anew.
        event.setCancelled(true);
        Map<EntityType, Integer> held = pendingQuality.get(player.getUniqueId());
        if (held != null) held.remove(mob);
        ItemStack[] matrix = inventory.getMatrix();
        for (int i = 0; i < matrix.length; i++) {
            if (matrix[i] != null && matrix[i].getAmount() > 0) {
                matrix[i].setAmount(matrix[i].getAmount() - 1);
            }
        }
        inventory.setMatrix(matrix);

        player.getInventory().addItem(shown.clone()).values().forEach(overflow ->
            player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        inventory.setResult(buildCard(player.getUniqueId(),
            headsAroundPaper(inventory.getMatrix(), vanillaHeads, heads)));
    }

    /**
     * Whether a card is this listener's result for a mob.
     *
     * <p>Case-blind because cards store the mob upper-cased ({@code ZOMBIE}). A
     * lower-cased compare never matched, so every click fell through to vanilla:
     * the preview was handed over as-is and the held roll was never spent, so
     * every craft of a mob came out at the same quality.
     */
    static boolean isCardOf(TradingCardData card, String mobName) {
        return card.mob() != null && card.mob().equalsIgnoreCase(mobName);
    }

    /** The eight surrounding heads name the craft's mob; everything else refuses it. */
    static EntityType headsAroundPaper(ItemStack[] matrix, boolean includeVanillaHeads,
                                       HeadType classifier) {
        if (matrix == null || matrix.length != 9) return null;
        ItemStack center = matrix[CENTER];
        if (center == null || center.getType() != Material.PAPER
            || TradingCardData.isCard(center)) {
            return null;
        }
        EntityType mob = null;
        for (int slot : SURROUNDING) {
            ItemStack head = matrix[slot];
            if (head == null) return null;
            EntityType type = classifier.mobOf(head, includeVanillaHeads);
            if (type == null) return null;
            if (mob == null) mob = type;
            else if (mob != type) return null;
        }
        return mob;
    }

    /** A Simple card of this mob at the player's held quality, or null when the mob has no card. */
    private ItemStack buildCard(UUID player, EntityType mob) {
        if (mob == null) return null;
        CardDefinition definition = module.registry()
            .byMob(mob.name().toLowerCase(Locale.ROOT)).orElse(null);
        if (definition == null) return null;
        var random = ThreadLocalRandom.current();
        int quality = pendingQuality.computeIfAbsent(player, id -> new HashMap<>())
            .computeIfAbsent(mob, type -> QualityBand.ROLL_MIN
                + random.nextInt(QualityBand.ROLL_MAX - QualityBand.ROLL_MIN + 1));
        QualityBand band = QualityBand.find(module.config().quality(), quality);
        if (band == null) return null;
        var rolled = new CardRoller.RolledCard(definition, Tier.SIMPLE, quality, band);
        return module.factory().create(rolled, module.config(), random).orElse(null);
    }
}
