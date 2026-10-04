package codes.castled.allium.frames;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.CardFrame;
import java.util.List;
import net.kyori.adventure.text.Component;

/** One of the built-in trading card frames, which have their own fixed art. */
record CardTierFrame(Tier tier) implements LineFrame {

    @Override
    public List<Component> item(List<Component> lines) {
        return CardFrame.wrap(tier, lines);
    }

    @Override
    public List<Component> hover(List<Component> lines) {
        return CardFrame.wrapHover(tier, lines);
    }
}
