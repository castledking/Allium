package codes.castled.allium.tradingcards.integration;

import codes.castled.allium.tradingcards.item.CardFrame;
import com.github.darksoulq.abyssallib.server.translation.ClientItemModifier;
import com.github.darksoulq.abyssallib.server.translation.internal.ItemPacketModifier;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps a framed card's header as the first line of its lore.
 *
 * <p>Relique registers a client-side item modifier with AbyssalLib that puts a
 * "Slot: ..." line in front of the lore of anything a relic slot accepts, which
 * is every trading card. The frame's header is drawn from the first lore line
 * upward, so a line in front of it pushed the ornament down over that line.
 * The card's lore already says where it is worn, so the line is dropped rather
 * than moved inside the frame.
 *
 * <p>AbyssalLib runs its modifiers in the order they were registered, and this
 * one is registered after the card slot is installed, which is after Relique has
 * enabled and registered its own.
 *
 * <p>Touches AbyssalLib classes directly, so only registered when the card slot
 * is installed.
 */
public final class CardTooltipGuard implements ClientItemModifier {

    private static boolean registered;

    private CardTooltipGuard() {}

    /** Registers the guard with AbyssalLib, once per server run. */
    public static synchronized void register() {
        if (!registered) {
            ItemPacketModifier.registerModifier(new CardTooltipGuard());
            registered = true;
        }
    }

    @Override
    public boolean modify(ItemStack item, Player player) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        List<Component> lore = item.lore();
        if (lore == null) {
            return false;
        }
        int header = headerIndex(lore);
        if (header <= 0) {
            return false;
        }
        item.lore(new ArrayList<>(lore.subList(header, lore.size())));
        return true;
    }

    /** The index of the frame's header line, or -1 when the lore is not framed. */
    static int headerIndex(List<Component> lore) {
        for (int i = 0; i < lore.size(); i++) {
            Component line = lore.get(i);
            if (line != null && CardFrame.FONT.equals(line.font())) {
                return i;
            }
        }
        return -1;
    }
}
