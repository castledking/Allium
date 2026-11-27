package codes.castled.allium.legacy;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class LegacyNicknameEditorTest {
    @Test
    void aColourAndStylesComposeAsAmpersandCodes() {
        assertEquals("&a&l&oBob", LegacyNicknameEditor.compose("Bob", 'a', null,
                EnumSet.of(LegacyNicknameEditor.Style.BOLD, LegacyNicknameEditor.Style.ITALIC)));
        assertEquals("&nBob", LegacyNicknameEditor.compose("Bob", null, null, EnumSet.of(LegacyNicknameEditor.Style.UNDERLINE)));
        assertEquals("Bob", LegacyNicknameEditor.compose(" Bob ", null, null, Collections.<LegacyNicknameEditor.Style>emptySet()));
        assertEquals("", LegacyNicknameEditor.compose("  ", 'a', null, Collections.<LegacyNicknameEditor.Style>emptySet()));
    }

    @Test
    void aGradientGivesEachLetterItsHexColourWithStylesAfterIt() {
        LegacyNicknameEditor.Gradient fire = LegacyNicknameEditor.GRADIENTS.get(1);
        String nick = LegacyNicknameEditor.compose("Abc", null, fire, EnumSet.of(LegacyNicknameEditor.Style.BOLD));
        assertEquals("&#FF3300&lA&#FF8800&lb&#FFEE33&lc", nick);
        assertEquals("FF5555", LegacyNicknameEditor.colourAt(Arrays.asList("#FF5555", "#000000"), 0));
        assertEquals("000000", LegacyNicknameEditor.colourAt(Arrays.asList("#FF5555", "#000000"), 1));
        assertEquals("802B2B", LegacyNicknameEditor.colourAt(Arrays.asList("#FF5555", "#000000"), 0.5));
    }

    @Test
    void everyColourHasItsOwnCode() {
        assertEquals(16, LegacyNicknameEditor.COLOURS.stream().map(c -> c.code).distinct().count());
    }
}
