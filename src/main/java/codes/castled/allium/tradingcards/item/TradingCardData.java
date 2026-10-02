package codes.castled.allium.tradingcards.item;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.tradingcards.card.CardDefinition;
import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Everything a card is, read from and written to its item stack.
 *
 * <p>A card is self-contained: mob, tier, level, quality and unlocked
 * signatures all live on the stack, so an offline card grants exactly what its
 * lore says it will and the server needs no per-player state to know what
 * someone is holding.
 *
 * <p>Lore is a rendering of these values, never a source of truth. It is
 * rebuilt from the definition on every write rather than appended to, so
 * repeated level-ups replace the level line instead of stacking a new one.
 */
public record TradingCardData(
    String mob,
    String cardId,
    Tier tier,
    int level,
    int quality,
    List<String> signatures,
    List<String> bonuses,
    double xp,
    int rerolls,
    boolean bound
) {

    /** A card with nothing but its three starting signatures and no banked xp. */
    public TradingCardData(String mob, String cardId, Tier tier, int level, int quality,
                           List<String> signatures, int rerolls, boolean bound) {
        this(mob, cardId, tier, level, quality, signatures, List.of(), 0.0, rerolls, bound);
    }

    /** A card with bonus boosts but no banked xp. */
    public TradingCardData(String mob, String cardId, Tier tier, int level, int quality,
                           List<String> signatures, List<String> bonuses,
                           int rerolls, boolean bound) {
        this(mob, cardId, tier, level, quality, signatures, bonuses, 0.0, rerolls, bound);
    }

    /** The band this card's quality falls in, from the current config. */
    public QualityBand band(List<QualityBand> bands) {
        return QualityBand.find(bands, quality);
    }

    /** The card's quality band id, or {@code unknown} if the bands do not cover it. */
    public String bandId(List<QualityBand> bands) {
        QualityBand band = band(bands);
        return band == null ? "unknown" : band.id();
    }

    /** Heads this card trades for at its current quality. */
    public int headValue(List<QualityBand> bands) {
        QualityBand band = band(bands);
        return band == null ? 0 : band.heads();
    }

    /** True when this stack carries the card marker. */
    public static boolean isCard(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(TradingCardKeys.CARD, PersistentDataType.BYTE);
    }

    /** Reads a card, or empty when the stack is not one. */
    public static Optional<TradingCardData> read(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return Optional.empty();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return Optional.empty();
        var pdc = meta.getPersistentDataContainer();
        if (!pdc.has(TradingCardKeys.CARD, PersistentDataType.BYTE)) {
            return Optional.empty();
        }
        String mob = pdc.get(TradingCardKeys.MOB, PersistentDataType.STRING);
        Tier tier = CardDefinition.parseTier(
            pdc.get(TradingCardKeys.TIER, PersistentDataType.STRING));
        if (mob == null || tier == null) {
            // The marker is present but the identity is not. Treating this as a
            // card would let a hand-edited or truncated stack grant boosts.
            return Optional.empty();
        }
        return Optional.of(new TradingCardData(
            mob,
            pdc.getOrDefault(TradingCardKeys.CARD_ID, PersistentDataType.STRING, ""),
            tier,
            pdc.getOrDefault(TradingCardKeys.LEVEL, PersistentDataType.INTEGER, 0),
            pdc.getOrDefault(TradingCardKeys.QUALITY, PersistentDataType.INTEGER, 100),
            readSignatures(pdc.get(TradingCardKeys.SIGNATURES, PersistentDataType.STRING)),
            readSignatures(pdc.get(TradingCardKeys.BONUSES, PersistentDataType.STRING)),
            pdc.getOrDefault(TradingCardKeys.XP, PersistentDataType.DOUBLE, 0.0D),
            pdc.getOrDefault(TradingCardKeys.REROLLS, PersistentDataType.INTEGER, 0),
            pdc.getOrDefault(TradingCardKeys.BOUND, PersistentDataType.BYTE, (byte) 0) == 1));
    }

    /**
     * Builds a fresh card item for a tier of a mob.
     *
     * @return empty when the tier has no configured item, or the item does not
     *         currently resolve — a card that drops an item nobody can hold is
     *         worse than no card at all, so the drop is skipped rather than
     *         handing out an unidentifiable stack
     */
    public static Optional<ItemStack> create(ItemResolverChain items, CardDefinition definition,
                                             Tier tier, int level, int quality,
                                             List<String> signatures, List<String> bonuses,
                                             boolean bound) {
        ItemRef ref = definition.itemFor(tier);
        if (ref == null) {
            return Optional.empty();
        }
        Optional<ItemStack> base = items.create(ref, 1);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        ItemStack stack = base.get();
        TradingCardData data = new TradingCardData(
            definition.mob(), definition.id(), tier, level, quality, signatures, bonuses, 0.0,
            0, bound);
        write(stack, data);
        return Optional.of(stack);
    }

    /**
     * The lore renderer, installed once when the module enables.
     *
     * <p>Static because every path that writes a card must render its lore, and
     * those paths are spread across classes this one does not own — the drop
     * factory, the reroll handler, the merge handler, and Relique's write-back.
     * Threading a renderer through all of them is more invasive and, more to the
     * point, easier to forget on the next path added. One global card format
     * genuinely is a global, so this is where the global belongs.
     *
     * <p>Null means write no lore, which is what happens when the module is
     * disabled: the PDC is still correct, so a card dropped before an enable is
     * still readable afterwards.
     */
    private static volatile CardLore lore;

    /** Installs the lore renderer. Pass null to stop rendering lore. */
    public static void lore(CardLore renderer) {
        lore = renderer;
    }

    /**
     * Writes the card state onto a stack in place, and marks it so
     * {@link #isCard} recognises it.
     */
    public static void write(ItemStack stack, TradingCardData data) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        pdc.set(TradingCardKeys.CARD, PersistentDataType.BYTE, (byte) 1);
        pdc.set(TradingCardKeys.MOB, PersistentDataType.STRING, data.mob());
        if (!data.cardId().isBlank()) {
            pdc.set(TradingCardKeys.CARD_ID, PersistentDataType.STRING, data.cardId());
        }
        pdc.set(TradingCardKeys.TIER, PersistentDataType.STRING, data.tier().name());
        pdc.set(TradingCardKeys.LEVEL, PersistentDataType.INTEGER, data.level());
        pdc.set(TradingCardKeys.QUALITY, PersistentDataType.INTEGER, data.quality());
        pdc.set(TradingCardKeys.SIGNATURES, PersistentDataType.STRING,
            String.join(",", data.signatures()));
        pdc.set(TradingCardKeys.BONUSES, PersistentDataType.STRING,
            String.join(",", data.bonuses()));
        pdc.set(TradingCardKeys.XP, PersistentDataType.DOUBLE, data.xp());
        pdc.set(TradingCardKeys.REROLLS, PersistentDataType.INTEGER, data.rerolls());
        pdc.set(TradingCardKeys.BOUND, PersistentDataType.BYTE, (byte) (data.bound() ? 1 : 0));
        meta.setMaxStackSize(1);
        renderLore(meta, data);
        stack.setItemMeta(meta);
        // After the meta is committed, because both are stack components and
        // setItemMeta can replace the stack's component set.
        CardTooltipStyle.apply(stack, data.tier());
        CardTooltipStyle.blankName(stack);
    }

    /**
     * Re-renders an already-written card's lore, for a config reload.
     *
     * <p>Lore embeds display names and the xp curve, so a rebalance leaves
     * existing cards quoting numbers that no longer apply. This puts them back
     * in step without touching the card's actual state.
     */
    public static void refreshLore(ItemStack stack, TradingCardData data) {
        if (stack == null || data == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        renderLore(meta, data);
        stack.setItemMeta(meta);
        // The tooltip's first line is the item name and the panel's ornament
        // sits in the rows above the text, so a title there overlapped the
        // ornament. See blankName for why this is item_name and not displayName.
        CardTooltipStyle.blankName(stack);
    }

    private static void renderLore(ItemMeta meta, TradingCardData data) {
        CardLore renderer = lore;
        if (renderer == null) {
            return;
        }
        // Rebuilt from the card every time rather than appended to, so repeated
        // writes replace the level line instead of stacking a new one on top.
        java.util.List<net.kyori.adventure.text.Component> lines =
            renderer.render(data, data.xp()).stream()
                .map(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()::deserialize)
                .collect(java.util.stream.Collectors.toList());
        meta.lore(lines);
    }

    /** A copy of this card at a different level. */
    public TradingCardData withLevel(int newLevel) {
        return new TradingCardData(mob, cardId, tier, newLevel, quality, signatures, bonuses,
            xp, rerolls, bound);
    }

    /** A copy of this card with a different banked xp toward its next level. */
    public TradingCardData withXp(double newXp) {
        return new TradingCardData(mob, cardId, tier, level, quality, signatures, bonuses,
            newXp, rerolls, bound);
    }

    /** A copy of this card one reroll further on. */
    public TradingCardData withReroll(int newRerolls, List<String> newSignatures) {
        return new TradingCardData(mob, cardId, tier, level, quality, newSignatures, bonuses,
            xp, newRerolls, bound);
    }

    /**
     * A copy of this card with different bonus boosts.
     *
     * <p>Bonuses change on a reroll and at nothing else. Levelling must not reach
     * this, which is why it is its own method rather than a general copy-with.
     */
    public TradingCardData withBonuses(List<String> newBonuses) {
        return new TradingCardData(mob, cardId, tier, level, quality, signatures,
            List.copyOf(newBonuses), xp, rerolls, bound);
    }

    /** True when the card has reached the level at which it can be merged. */
    public boolean isMergeable(int maximumLevel) {
        return level >= maximumLevel;
    }

    /** Every tier's entry point to this card's mob, for an inspect command. */
    public static Map<Tier, String> describeTiers() {
        Map<Tier, String> out = new LinkedHashMap<>();
        for (Tier tier : Tier.values()) {
            out.put(tier, tier.name().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    private static List<String> readSignatures(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : Arrays.asList(raw.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return List.copyOf(out);
    }
}
