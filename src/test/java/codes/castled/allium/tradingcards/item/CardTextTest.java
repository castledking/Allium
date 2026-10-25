package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CardTextTest {
    @Test
    void visibleLettersBecomeSmallCapsWithoutChangingFormattingOrCommands() {
        assertEquals("<aqua>ʙᴏɴᴜꜱ <white>+25% <gray>/reliques",
            CardText.smallCaps("<aqua>Bonus <white>+25% <gray>/reliques"));
        assertEquals("<font:sf:card_frame>᪗◆🔒</font>",
            CardText.smallCaps("<font:sf:card_frame>᪗◆🔒</font>"));
    }

    @Test
    void quotedTagArgumentsAndExistingSmallCapsSurvive() {
        String text = "<hover:show_text:'Use <aqua>Luck'>Bonus</hover>";
        String rendered = CardText.smallCaps(text);
        assertEquals("<hover:show_text:'Use <aqua>Luck'>ʙᴏɴᴜꜱ</hover>", rendered);
        assertEquals(rendered, CardText.smallCaps(rendered));
    }
}
