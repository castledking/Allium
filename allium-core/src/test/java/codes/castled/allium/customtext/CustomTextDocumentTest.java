package codes.castled.allium.customtext;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomTextDocumentTest {

    @Test
    void sectionsAliasesCommentsAndTheIndexFollowTheEssentialsLayout() {
        CustomTextDocument doc = CustomTextDocument.parse(List.of(
                "## a comment",
                "&6Welcome",
                "",
                "#Vote,voting, Top Voters",
                "Vote here",
                "",
                "#rules",
                "1. Be kind\r",
                "",
                ""));
        assertEquals(List.of("&6Welcome"), doc.index());
        assertEquals(List.of("Vote here"), doc.section("VOTING").orElseThrow());
        assertEquals(List.of("Vote here"), doc.section("top_voters").orElseThrow());
        assertEquals("vote", doc.primaryName("voting").orElseThrow());
        assertEquals(List.of("1. Be kind"), doc.section("rules").orElseThrow());
        assertEquals(List.of("vote", "voting", "top_voters", "rules"), doc.sectionNames());
        assertTrue(doc.section("missing").isEmpty());
    }

    @Test
    void aSectionLongerThanAPageSplitsIntoNineLinePagesAndClampsThePage() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 20; i++) lines.add("line " + i);
        CustomTextDocument.Page first = CustomTextDocument.page(lines, 1);
        assertEquals(3, first.pages());
        assertEquals(9, first.lines().size());
        CustomTextDocument.Page last = CustomTextDocument.page(lines, 99);
        assertEquals(3, last.page());
        assertEquals(List.of("line 19", "line 20"), last.lines());
        assertEquals(1, CustomTextDocument.page(List.of("only"), 5).pages());
    }
}
