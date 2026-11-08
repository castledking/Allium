package codes.castled.allium.messaging;

import codes.castled.allium.text.FormattedText;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UntrustedTextTest {

    private static final UntrustedText.Allow NONE = UntrustedText.Allow.NONE;
    private static final UntrustedText.Allow COLOR = new UntrustedText.Allow(true, false, false);
    private static final UntrustedText.Allow HEX = new UntrustedText.Allow(false, true, false);
    private static final UntrustedText.Allow GRADIENT = new UntrustedText.Allow(false, false, true);
    private static final UntrustedText.Allow ALL = new UntrustedText.Allow(true, true, true);

    private static boolean unstyled(FormattedText text) {
        return text.glyphs().stream().allMatch(g -> g.rgb() == -1 && g.legacyColorChar() == '\0' && g.decorations() == 0);
    }

    private static Set<Integer> colors(FormattedText text) {
        Set<Integer> out = new HashSet<>();
        text.glyphs().forEach(g -> out.add(g.rgb()));
        return out;
    }

    @Test
    void withoutPermissionEveryFormattingSequenceIsShownLiterally() {
        String typed = "&cred &lbold &#ff0000hex &x&f&f&0&0&0&0bungee #00ff00bare <#123456>tag</#123456> "
                + "<gradient:#ff0000:#0000ff>grad</gradient> <rainbow>rb</rainbow> <reset> <b>b</b> <italic>i</italic> <obf>o</obf>";
        FormattedText out = UntrustedText.parse(typed, NONE);
        assertEquals(typed, out.toPlainText(), "exactly as typed");
        assertTrue(unstyled(out));
    }

    @Test
    void eachPermissionUnlocksOnlyItsOwnFormatting() {
        FormattedText color = UntrustedText.parse("&cred #00ff00bare <gradient:#ff0000:#0000ff>g</gradient>", COLOR);
        assertEquals("red #00ff00bare <gradient:#ff0000:#0000ff>g</gradient>", color.toPlainText());
        assertEquals(Set.of(0xFF5555), Set.of(color.glyphs().get(0).rgb()), "the & code applies");

        FormattedText hex = UntrustedText.parse("&cred &#ff0000hex <rainbow>r</rainbow>", HEX);
        assertEquals("&cred hex <rainbow>r</rainbow>", hex.toPlainText());
        assertTrue(colors(hex).contains(0xFF0000));

        FormattedText gradient = UntrustedText.parse("<gradient:#ff0000:#0000ff>ab</gradient> &cno", GRADIENT);
        assertEquals("ab &cno", gradient.toPlainText());
        assertEquals(0xFF0000, gradient.glyphs().get(0).rgb());
    }

    @Test
    void injectionAttemptsStayPlainText() {
        String[] attempts = {
                "<click:run_command:/op Mallory>free diamonds</click>",
                "<hover:show_text:'pwned'>hi</hover>",
                "{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op Mallory\"}}",
                "<insert:/op Mallory>x</insert>",
                "[x](/op Mallory)",
        };
        for (String attempt : attempts) {
            FormattedText out = UntrustedText.parse(attempt, ALL);
            assertEquals(attempt, out.toPlainText(), "unchanged text, even with every formatting permission: " + attempt);
            assertTrue(unstyled(out), attempt);
        }
    }

    @Test
    void sectionSignsControlCharactersAndPlaceholdersAreRemoved() {
        FormattedText out = UntrustedText.parse("a§cb\nc\rd\u0000efg\tz", ALL);
        assertEquals("acbcdefgz", out.toPlainText(), "the § goes; the c it would have applied stays as text");
        assertTrue(unstyled(out), "a removed § cannot style the rest");
        assertFalse(out.toLegacy().contains("§c"), "no § formatting reaches Spigot's legacy path");
    }

    @Test
    void aNeutralizedCodeCannotRebuildAnotherAfterRestoration() {
        // Without hex, "&#ff0000" must not decay into a bare "#ff0000" that the formatter would apply.
        FormattedText out = UntrustedText.parse("&#ff0000x <#00ff00>y", COLOR);
        assertEquals("&#ff0000x <#00ff00>y", out.toPlainText());
        assertTrue(unstyled(out));
    }

    @Test
    void theBodyGoesIntoATrustedTemplateAndInheritsItsColor() {
        FormattedText body = UntrustedText.parse("&chi <b>", NONE);
        FormattedText line = UntrustedText.insert("&7[&fAlice &7→ &fme&7] &f{message}", body);
        assertEquals("[Alice → me] &chi <b>", line.toPlainText());
        List<FormattedText.StyledGlyph> glyphs = line.glyphs();
        FormattedText.StyledGlyph amp = glyphs.get(line.toPlainText().indexOf('&'));
        assertEquals('f', amp.legacyColorChar(), "the literal body takes the template's &f");
        FormattedText colored = UntrustedText.insert("&f{message}", UntrustedText.parse("&cred", COLOR));
        assertEquals('c', colored.glyphs().get(0).legacyColorChar(), "the body's own colour wins");
    }
}
