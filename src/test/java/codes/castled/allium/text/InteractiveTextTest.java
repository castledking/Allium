package codes.castled.allium.text;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveTextTest {

    private static String plain(BaseComponent[] parts) {
        StringBuilder out = new StringBuilder();
        for (BaseComponent part : parts) out.append(part.toPlainText());
        return out.toString();
    }

    private static BaseComponent part(BaseComponent[] parts, String text) {
        return Arrays.stream(parts).filter(p -> p.toPlainText().equals(text)).findFirst().orElseThrow(() -> new AssertionError(text));
    }

    @Test
    void clickAndHoverTagsMarkOnlyTheirTextAndFormattingCarriesAcrossThem() {
        BaseComponent[] parts = InteractiveText.components(
                "&aHello <click:run_command:/spawn><hover:show_text:'&eGo\\nnow'>&lworld</hover></click> done");
        assertEquals("Hello world done", plain(parts));

        BaseComponent hello = part(parts, "Hello ");
        assertNull(hello.getClickEvent());
        assertEquals(ChatColor.GREEN, hello.getColor());

        BaseComponent world = part(parts, "world");
        assertEquals(ClickEvent.Action.RUN_COMMAND, world.getClickEvent().getAction());
        assertEquals("/spawn", world.getClickEvent().getValue());
        assertNotNull(world.getHoverEvent());
        assertTrue(world.isBold());
        assertEquals(ChatColor.GREEN, world.getColor(), "the colour before the tag still applies inside it");

        BaseComponent done = part(parts, " done");
        assertNull(done.getClickEvent());
        assertNull(done.getHoverEvent());
    }

    @Test
    void everyClickActionParsesAndUnsafeOrUnknownTagsStayText() {
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND,
                part(InteractiveText.components("<click:suggest_command:/msg >Ask</click>"), "Ask").getClickEvent().getAction());
        assertEquals("/msg ", part(InteractiveText.components("<click:suggest_command:/msg >Ask</click>"), "Ask").getClickEvent().getValue());
        assertEquals("https://example.com/a:b",
                part(InteractiveText.components("<click:open_url:'https://example.com/a:b'>Site</click>"), "Site").getClickEvent().getValue());
        assertEquals(ClickEvent.Action.COPY_TO_CLIPBOARD,
                part(InteractiveText.components("<click:copy_to_clipboard:play.example.com>IP</click>"), "IP").getClickEvent().getAction());

        BaseComponent[] unsafe = InteractiveText.components("<click:open_url:javascript:alert(1)>x</click>");
        assertTrue(plain(unsafe).startsWith("<click:open_url:javascript:alert(1)>x"), "a non-http link is not a click tag");
        assertTrue(Arrays.stream(unsafe).allMatch(p -> p.getClickEvent() == null));
        assertEquals("I <3 you", plain(InteractiveText.components("I <3 you")));
        assertEquals("[Rules]", plain(InteractiveText.components("[Rules]")));
    }

    @Test
    void aWholeJsonLineIsSentAsVanillaTextAndTheConsoleGetsPlainText() {
        BaseComponent[] json = InteractiveText.components(
                "{\"text\":\"Vote\",\"color\":\"gold\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/vote\"}}");
        assertEquals("Vote", plain(json));
        assertEquals("/vote", json[0].getClickEvent().getValue());
        assertEquals("ab", plain(InteractiveText.components("[{\"text\":\"a\"},{\"text\":\"b\"}]")));

        assertEquals("Hello world", InteractiveText.formatted("&aHello <click:run_command:/spawn>world</click>").toPlainText());
        assertEquals("Vote", InteractiveText.formatted("{\"text\":\"Vote\",\"color\":\"gold\"}").toPlainText());
        assertEquals(List.of(), InteractiveText.formatted("").glyphs());
    }

    @Test
    void backslashEscapesShowCodesTagsAndPlaceholdersAsWritten() {
        String line = InteractiveText.escape("&a\\&c red? \\&#ff7a00 \\#33ccff \\<rainbow>x\\</rainbow> \\{player} \\%name% \\\\ \\q");
        BaseComponent[] parts = InteractiveText.components(line);
        assertEquals("&c red? &#ff7a00 #33ccff <rainbow>x</rainbow> {player} %name% \\ \\q", plain(parts));
        for (BaseComponent part : parts) {
            assertEquals(ChatColor.GREEN, part.getColor(), "an escaped code changes nothing: " + part.toPlainText());
        }
        assertEquals("&c red? &#ff7a00 #33ccff <rainbow>x</rainbow> {player} %name% \\ \\q", InteractiveText.formatted(line).toPlainText());

        BaseComponent[] tag = InteractiveText.components(InteractiveText.escape("\\<click:run_command:/spawn>Spawn\\</click>"));
        assertEquals("<click:run_command:/spawn>Spawn</click>", plain(tag));
        assertNull(tag[0].getClickEvent());

        BaseComponent[] inside = InteractiveText.components(InteractiveText.escape(
                "<click:suggest_command:/say \\&a><hover:show_text:'Type \\&a\\nnext line'>Go</hover></click>"));
        assertEquals("/say &a", part(inside, "Go").getClickEvent().getValue());
        Object hover = ((net.md_5.bungee.api.chat.hover.content.Text) part(inside, "Go").getHoverEvent().getContents().get(0)).getValue();
        assertEquals("Type &a\nnext line", plain((BaseComponent[]) hover));

        String json = "{\"text\":\"a \\\\ b\"}";
        assertEquals(json, InteractiveText.escape(json), "JSON lines are left alone");
    }
}
