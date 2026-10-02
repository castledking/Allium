package codes.castled.allium.tradingcards.item;

import codes.castled.allium.tradingcards.card.Tier;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/**
 * Gives a card a tooltip panel that matches its tier and fits its lore.
 *
 * <p>Minecraft has a per-item tooltip style component,
 * {@code minecraft:tooltip_style}, holding a namespaced id. The client resolves
 * that id to a pair of GUI sprites — {@code <ns>:tooltip/<id>_background} and
 * {@code <ns>:tooltip/<id>_frame} — and draws each across the tooltip quad.
 *
 * <p>The panel is NOT nine-sliced, and it does not grow with the lore. A pack
 * shader overrides {@code core/position_tex_color}, which is the pipeline the
 * tooltip is drawn through, and draws the background sprite 1:1 from the quad's
 * top-left, stopping at a height carried in the sprite's own metadata row. So
 * the frame ends where the card's own text ends and the client's F3+H lines fall
 * outside it, which no nine-slice can do — a nine-slice is handed the whole quad
 * and stretches to fill it.
 *
 * <p>Because the height is fixed per sprite, it has to vary with the lore, which
 * is why the id carries a line count and {@link #FRAME_BASE} exists. See the
 * constants below for how the height is arrived at.
 *
 * <p>Why a per-item style at all: the alternative is the global
 * {@code gui/sprites/tooltip/background}, one sprite for the whole client —
 * changing it restyles every tooltip on the server, and no item can pick a
 * different one. {@code tooltip_style} (1.21.2) is the only way to make one
 * item's tooltip differ from another's.
 *
 * <p>The component lives on the {@link ItemStack} rather than the
 * {@code ItemMeta}, because Paper exposes arbitrary data components on the stack
 * and not on the meta.
 *
 * <p>Failures are silent by design. A client without the resource pack shows the
 * default tooltip, which is a cosmetic downgrade rather than an unreadable card,
 * so there is nothing here worth failing a card write over. The corollary is
 * that a style id with no sprite behind it is equally silent, which is why the
 * line count clamps to the generated range.
 */
public final class CardTooltipStyle {

    /** The namespace the sprite pair lives under. */
    public static final String NAMESPACE = "sf";

    /**
     * Frame height, in GUI pixels, is {@code FRAME_BASE + LINE_PITCH * lines}.
     *
     * <p>Lore length varies per card — every signature is a line and the xp row
     * is optional — and the shader draws the panel 1:1 and stops at this height,
     * so one constant would leave a tall panel on a short card and clip the last
     * line of a long one.
     *
     * <p>The base has to clear the text AND the whole bottom cap. Sizing it to
     * the text alone put the cap's top rule straight through the last lore line,
     * because the cap is 15 art rows drawn 1:1 at the bottom and there is only
     * about 3px between the lore and the quad's bottom edge.
     */
    public static final int TEXT_TOP = 25;
    public static final int BOTTOM_CAP = 15;
    public static final int CAP_GAP = 2;
    public static final int LINE_PITCH = 10;
    public static final int FRAME_BASE = TEXT_TOP + BOTTOM_CAP + CAP_GAP;
    private static final int MIN_LINES = 15;
    private static final int MAX_LINES = 30;

    private CardTooltipStyle() {}

    private static String styleId(Tier tier, int lines) {
        int n = Math.clamp(lines, MIN_LINES, MAX_LINES);
        return "card_" + tier.name().toLowerCase(Locale.ROOT) + "_" + n;
    }

    /**
     * The style id a card of this tier uses.
     *
     * <p>Lowercased, because the client resolves the sprite path through the id
     * verbatim and the asset directory on disk is lowercase — {@code sf:card_fabled}
     * finds {@code card_fabled_background.png}, while {@code sf:card_FABLED} would
     * not.
     */
    public static Key styleFor(Tier tier, int lines) {
        return Key.key(NAMESPACE, styleId(tier == null ? Tier.SIMPLE : tier, lines));
    }

    /**
     * Applies the tier's tooltip style to a card.
     *
     * <p>Rewritten on every write rather than only on creation, because the tier
     * is part of the card's state and can change through a merge — a merged card
     * has to leave the LORE tier's panel behind, or it takes two cards' worth of
     * chrome with it.
     */
    public static void apply(ItemStack stack, Tier tier, int loreLines) {
        if (stack == null) {
            return;
        }
        try {
            stack.setData(DataComponentTypes.TOOLTIP_STYLE, styleFor(tier, loreLines));
        } catch (Throwable ignored) {
            // A Paper build without the component, or an item the stack cannot
            // carry one. The card is still correct; it just uses the default
            // tooltip, and saying so once per write would be noise.
        }
    }

    /**
     * Blanks the tooltip's first line, which is the item's name.
     *
     * <p>Set as {@code item_name}, not through {@code ItemMeta.displayName}:
     * that writes {@code custom_name}, and {@code item_name} takes precedence
     * for the tooltip, so the item definition's name kept winning and the card
     * showed its title twice. A single space rather than an empty component,
     * because an empty name is dropped by some paths and the item falls back to
     * the material's own name.
     *
     * <p>Called after the meta is committed, since it is a stack component and
     * {@code setItemMeta} can replace the stack's component set.
     */
    public static void blankName(ItemStack stack) {
        if (stack == null) {
            return;
        }
        try {
            stack.setData(DataComponentTypes.ITEM_NAME, Component.text(" "));
        } catch (Throwable ignored) {
            // As above: a missing component costs the blank line, not the card.
        }
    }
}
