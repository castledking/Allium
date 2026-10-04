package codes.castled.allium.tradingcards.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.inventory.ItemStack;

/**
 * Clears vanilla's tooltip panel off a card and blanks its name line, so the
 * panel {@link CardFrame} draws in the lore is the only one showing.
 *
 * <p>Minecraft has a per-item tooltip style component,
 * {@code minecraft:tooltip_style}, holding a namespaced id. The client resolves
 * that id to a pair of GUI sprites — {@code <ns>:tooltip/<id>_background} and
 * {@code <ns>:tooltip/<id>_frame} — and draws them behind the text. The pack
 * ships both as empty sprites for {@code sf:card}, so a card draws no panel of
 * its own and the frame glyphs are what show.
 *
 * <p>Why a per-item style at all: the alternative is the global
 * {@code gui/sprites/tooltip/background}, one sprite for the whole client, and
 * blanking it would strip the panel off every other item on the server.
 *
 * <p>Both components live on the {@link ItemStack} rather than the
 * {@code ItemMeta}, because Paper exposes arbitrary data components on the stack
 * and not on the meta.
 *
 * <p>Failures are silent by design. A client without the resource pack shows the
 * default tooltip, which is a cosmetic downgrade rather than an unreadable card,
 * so there is nothing here worth failing a card write over.
 */
public final class CardTooltipStyle {

    /** The namespace the sprite pair and the name key live under. */
    public static final String NAMESPACE = "sf";

    /**
     * The style every card uses. One for all tiers: the panel is drawn by the
     * lore now, and the tier's art is picked there.
     */
    public static final Key STYLE = Key.key(NAMESPACE, "card");

    private CardTooltipStyle() {}

    /**
     * Applies the card tooltip style.
     *
     * <p>Rewritten on every write rather than only on creation, so a card
     * written before the panel moved into the lore picks up the new style the
     * next time anything touches it.
     */
    public static void apply(ItemStack stack) {
        if (stack == null) {
            return;
        }
        try {
            stack.setData(DataComponentTypes.TOOLTIP_STYLE, STYLE);
        } catch (Throwable ignored) {
            // A Paper build without the component, or an item the stack cannot
            // carry one. The card is still correct; it just uses the default
            // tooltip, and saying so once per write would be noise.
        }
    }

    /**
     * Blanks the tooltip's first line, the item's name.
     *
     * <p>The name line has to stay, blank, because {@link CardFrame}'s header
     * reaches up through it. The title itself is in the lore, under the header.
     *
     * <p>A blank string, not a translation the pack maps to nothing. AbyssalLib
     * translates item names on the server before they are sent, and for a key it
     * does not know it substitutes the fallback, so in any container window the
     * card's title came back as a visible name above the frame. A single space
     * rather than an empty component, because an empty name is dropped by some
     * paths and the item falls back to the material's own name.
     *
     * <p>Set as {@code item_name}, not through {@code ItemMeta.displayName}:
     * that writes {@code custom_name}, and {@code item_name} takes precedence
     * for the tooltip, so the item definition's name kept winning and the card
     * showed its title twice.
     *
     * <p>Called after the meta is committed, since it is a stack component and
     * {@code setItemMeta} can replace the stack's component set.
     */
    public static void name(ItemStack stack) {
        if (stack == null) {
            return;
        }
        try {
            stack.setData(DataComponentTypes.ITEM_NAME, hiddenName());
        } catch (Throwable ignored) {
            // As above: a missing component costs the blank line, not the card.
        }
    }

    /**
     * The name a framed item carries, blank so the frame's header can reach up
     * through its line.
     *
     * <p>Public for the frame marker, which puts it on items it does not own.
     */
    public static Component hiddenName() {
        return Component.text(" ");
    }

    /** The first colour set anywhere in a component, or null. */
    static TextColor colourOf(Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor colour = colourOf(child);
            if (colour != null) {
                return colour;
            }
        }
        return null;
    }
}
