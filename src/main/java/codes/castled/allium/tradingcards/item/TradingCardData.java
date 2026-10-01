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
    int rerolls,
    boolean bound
) {

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
                                             List<String> signatures, boolean bound) {
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
            definition.mob(), definition.id(), tier, level, quality, signatures, 0, bound);
        write(stack, data);
        return Optional.of(stack);
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
        pdc.set(TradingCardKeys.REROLLS, PersistentDataType.INTEGER, data.rerolls());
        pdc.set(TradingCardKeys.BOUND, PersistentDataType.BYTE, (byte) (data.bound() ? 1 : 0));
        // Two cards of the same mob and tier differ in level, quality and
        // signatures, so they must never merge into one stack — a merged
        // stack would silently discard the extra cards' state.
        meta.setMaxStackSize(1);
        stack.setItemMeta(meta);
    }

    /** A copy of this card at a different level. */
    public TradingCardData withLevel(int newLevel) {
        return new TradingCardData(mob, cardId, tier, newLevel, quality, signatures, rerolls, bound);
    }

    /** A copy of this card one reroll further on. */
    public TradingCardData withReroll(int newRerolls, List<String> newSignatures) {
        return new TradingCardData(mob, cardId, tier, level, quality, newSignatures,
            newRerolls, bound);
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
