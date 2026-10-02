package codes.castled.allium.tradingcards.item;

import codes.castled.allium.tradingcards.card.Tier;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;

/**
 * Gives a card a tooltip background that matches its tier.
 *
 * <p>Minecraft has a per-item tooltip style component,
 * {@code minecraft:tooltip_style}, holding a namespaced id. The client resolves
 * that id to a pair of GUI sprites — {@code <ns>:tooltip/<path>_background} and
 * {@code <ns>:tooltip/<path>_frame} — and nine-slices each to whatever size the
 * lore needs. So the panel grows with the card's text on the client, with no
 * fixed-size image to guess at.
 *
 * <p>This is why the per-tier background is worth having here. The other
 * candidate, the global {@code gui/sprites/tooltip/background}, is a single
 * sprite for the entire client — changing it restyles every tooltip on the
 * server, and no item can pick a different one. {@code tooltip_style} was added
 * in 1.21.2 and is the only way to make one item's tooltip differ from another's.
 *
 * <p>The component lives on the {@link ItemStack} rather than the
 * {@code ItemMeta}, because Paper exposes arbitrary data components on the stack
 * and not on the meta.
 *
 * <p>Failures are silent by design. A client without the resource pack shows the
 * default tooltip, which is a cosmetic downgrade rather than an unreadable card,
 * so there is nothing here worth failing a card write over.
 */
public final class CardTooltipStyle {

    /** The namespace the sprite pair lives under. */
    public static final String NAMESPACE = "sf";

    private static final Map<Tier, Key> STYLES = styles();

    private CardTooltipStyle() {}

    private static Map<Tier, Key> styles() {
        Map<Tier, Key> map = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) {
            map.put(tier, Key.key(NAMESPACE, "card_" + tier.name().toLowerCase(Locale.ROOT)));
        }
        return Map.copyOf(map);
    }

    /**
     * The style id a card of this tier uses.
     *
     * <p>Lowercased, because the client resolves the sprite path through the id
     * verbatim and the asset directory on disk is lowercase — {@code sf:card_fabled}
     * finds {@code card_fabled_background.png}, while {@code sf:card_FABLED} would
     * not.
     */
    public static Key styleFor(Tier tier) {
        return STYLES.get(tier == null ? Tier.SIMPLE : tier);
    }

    /**
     * Applies the tier's tooltip style to a card.
     *
     * <p>Rewritten on every write rather than only on creation, because the tier
     * is part of the card's state and can change through a merge — a merged card
     * has to leave the LORE tier's panel behind, or it takes two cards' worth of
     * chrome with it.
     */
    public static void apply(ItemStack stack, Tier tier) {
        if (stack == null) {
            return;
        }
        try {
            stack.setData(DataComponentTypes.TOOLTIP_STYLE, styleFor(tier));
        } catch (Throwable ignored) {
            // A Paper build without the component, or an item the stack cannot
            // carry one. The card is still correct; it just uses the default
            // tooltip, and saying so once per write would be noise.
        }
    }
}
