package codes.castled.allium.chat;

import codes.castled.allium.text.TextFormatter;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NicknameEditorGuiTest {

    @Test
    void composeFormatsColorsStylesAndGradientsCleanly() {
        assertEquals("Alice", NicknameEditorGui.compose("Alice", null, null, Set.of()));
        assertEquals("&bAlice", NicknameEditorGui.compose("Alice", 'b', null, Set.of()));
        assertEquals("&b&l&oAlice", NicknameEditorGui.compose("Alice", 'b', null,
                EnumSet.of(NicknameEditorGui.StyleFlag.BOLD, NicknameEditorGui.StyleFlag.ITALIC)));

        var fire = NicknameEditorGui.GRADIENTS.stream()
                .filter(g -> g.id().equals("fire")).findFirst().orElseThrow();
        String gradientNick = NicknameEditorGui.compose("Alice", 'b', fire,
                EnumSet.of(NicknameEditorGui.StyleFlag.BOLD));
        assertEquals("&l<gradient:#FF3300:#FF8800:#FFEE33>Alice</gradient>", gradientNick);
        assertEquals("Alice", TextFormatter.parse(gradientNick).toPlainText());
    }

    @Test
    void stripPlainBaseRemovesExistingFormattingWithoutStackingTags() {
        assertEquals("Castled", NicknameEditorGui.stripPlainBase("<gradient:#FF0000:#00FF00>&lCastled</gradient>", "Fallback"));
        assertEquals("Castled", NicknameEditorGui.stripPlainBase("&a&lCastled", "Fallback"));
        assertEquals("Fallback", NicknameEditorGui.stripPlainBase(null, "Fallback"));
        assertEquals("Fallback", NicknameEditorGui.stripPlainBase("&a&l", "Fallback"));
    }
}
