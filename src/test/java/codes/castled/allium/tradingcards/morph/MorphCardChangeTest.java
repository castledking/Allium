package codes.castled.allium.tradingcards.morph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.List;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/** A morph lasts only as long as the card slot holds a card that could grant it. */
class MorphCardChangeTest {

    private static TradingCardData card(String mob, Tier tier) {
        return new TradingCardData(mob, mob.toLowerCase(), tier, 100, 80,
            List.of("luck", "strength", "speed"), 0, false);
    }

    @Test
    void anEmptySlotEndsTheMorph() {
        assertFalse(MorphService.keepsForm(EntityType.ZOMBIE, null, Tier.FABLED),
            "unequipping the card that granted a morph must take the morph with it");
    }

    @Test
    void theSameCardStillInTheSlotKeepsIt() {
        assertTrue(MorphService.keepsForm(EntityType.ZOMBIE, card("ZOMBIE", Tier.FABLED), Tier.FABLED));
    }

    @Test
    void swappingToAnotherMobsCardEndsIt() {
        assertFalse(MorphService.keepsForm(EntityType.ZOMBIE, card("SKELETON", Tier.FABLED), Tier.FABLED));
    }

    @Test
    void swappingToALowerTierOfTheSameMobEndsIt() {
        assertFalse(MorphService.keepsForm(EntityType.ZOMBIE, card("ZOMBIE", Tier.LEGENDARY), Tier.FABLED),
            "a card that could not start this morph must not keep it going");
    }
}
