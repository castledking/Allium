package codes.castled.allium.tradingcards.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.CardFrame;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * Finding the frame's header in lore another plugin has written in front of.
 *
 * <p>Relique puts a "Slot: ..." line ahead of every card's lore on its way to
 * the client, which pushed the header, and the ornament it draws upward, down
 * over that line.
 */
class CardTooltipGuardTest {

    @Test
    void theHeaderIsFoundBehindALineSomeoneElseAdded() {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Slot: Card"));
        lore.addAll(CardFrame.wrap(Tier.FABLED, List.of(Component.text("Allay Trading Card"))));
        assertEquals(1, CardTooltipGuard.headerIndex(lore));
    }

    @Test
    void unframedLoreAndLoreThatAlreadyStartsWithTheHeaderAreLeftAlone() {
        assertEquals(-1, CardTooltipGuard.headerIndex(List.of(Component.text("Slot: Ring"))));
        assertEquals(0, CardTooltipGuard.headerIndex(
            CardFrame.wrap(Tier.SIMPLE, List.of(Component.text("Pig Trading Card")))));
    }
}
