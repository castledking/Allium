package codes.castled.allium.managers.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatColorParserTest {

    private static ChatColorParser.PermissionCheck granting(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return granted::contains;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Colour of the first leaf that carries one. */
    private static TextColor firstColor(Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor color = firstColor(child);
            if (color != null) {
                return color;
            }
        }
        return null;
    }

    private static boolean anyBold(Component component) {
        if (component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
            return true;
        }
        return component.children().stream().anyMatch(ChatColorParserTest::anyBold);
    }

    @Test
    void hexAndFormatCodesSurviveTogether() {
        String mini = ChatColorParser.toMiniMessage(ChatColorParser.ALLOW_ALL,
                "&#8C8F9B&lv&#9A9CA5&li&#A7A8AE&lp");

        assertEquals("<reset><#8C8F9B><bold>v<reset><#9A9CA5><bold>i<reset><#A7A8AE><bold>p", mini);

        Component parsed = ChatColorParser.parse(ChatColorParser.ALLOW_ALL,
                "&#8C8F9B&lv&#9A9CA5&li&#A7A8AE&lp");
        assertEquals("vip", plain(parsed));
        assertEquals(TextColor.fromHexString("#8C8F9B"), firstColor(parsed));
        assertTrue(anyBold(parsed));
    }

    @Test
    void legacyColourCodeIsApplied() {
        Component parsed = ChatColorParser.parse(ChatColorParser.ALLOW_ALL, "&dvip");

        assertEquals("vip", plain(parsed));
        assertEquals(NamedTextColor.LIGHT_PURPLE, firstColor(parsed));
    }

    @Test
    void legacyAndMiniMessageMixInOneMessage() {
        Component parsed = ChatColorParser.parse(ChatColorParser.ALLOW_ALL,
                "&dhello <gradient:#FF0000:#00FF00>world</gradient>");

        assertEquals("hello world", plain(parsed));
        assertEquals(NamedTextColor.LIGHT_PURPLE, firstColor(parsed));
    }

    @Test
    void spigotXHexFormIsUnderstood() {
        assertEquals("<reset><#8C8F9B>v",
                ChatColorParser.toMiniMessage(ChatColorParser.ALLOW_ALL, "&x&8&C&8&F&9&Bv"));
        assertEquals("<reset><#8C8F9B>v",
                ChatColorParser.toMiniMessage(ChatColorParser.ALLOW_ALL, "§x§8§C§8§F§9§Bv"));
    }

    @Test
    void colourCodesAreDroppedWithoutPermission() {
        Component parsed = ChatColorParser.parse(ChatColorParser.DENY_ALL, "&#8C8F9B&lvip");

        assertEquals("vip", plain(parsed));
        assertEquals(null, firstColor(parsed));
        assertFalse(anyBold(parsed));
    }

    @Test
    void perColourPermissionsAreHonoured() {
        ChatColorParser.PermissionCheck onlyRed = granting("chat.color.red");

        assertEquals("<reset><red>red then blue",
                ChatColorParser.toMiniMessage(onlyRed, "&cred then &9blue"));
    }

    @Test
    void hexNeedsItsOwnPermission() {
        ChatColorParser.PermissionCheck noHex = granting("chat.color.red");
        assertEquals("<reset><red>hi", ChatColorParser.toMiniMessage(noHex, "&#8C8F9B&chi"));

        ChatColorParser.PermissionCheck withHex = granting("chat.color.hex", "chat.color.red");
        assertEquals("<reset><#8C8F9B><reset><red>hi",
                ChatColorParser.toMiniMessage(withHex, "&#8C8F9B&chi"));
    }

    @Test
    void miniMessageTagsAreStrippedWithoutPermissionButLegacySurvives() {
        ChatColorParser.PermissionCheck legacyOnly = granting("chat.color");

        assertEquals("<reset><light_purple>hello world",
                ChatColorParser.toMiniMessage(legacyOnly, "&dhello <rainbow>world</rainbow>"));
    }

    @Test
    void miniMessagePermissionsAreCheckedPerTag() {
        ChatColorParser.PermissionCheck noRainbow = granting("chat.minimessage", "chat.minimessage.color");
        assertEquals("world", ChatColorParser.toMiniMessage(noRainbow, "<rainbow>world</rainbow>"));

        ChatColorParser.PermissionCheck withRainbow = granting("chat.minimessage", "chat.minimessage.rainbow");
        assertEquals("<rainbow>world</rainbow>",
                ChatColorParser.toMiniMessage(withRainbow, "<rainbow>world</rainbow>"));
    }

    @Test
    void literalAmpersandIsKept() {
        assertEquals("Tom & Jerry", ChatColorParser.toMiniMessage(ChatColorParser.ALLOW_ALL, "Tom & Jerry"));
        assertEquals("50% off &&", ChatColorParser.toMiniMessage(ChatColorParser.ALLOW_ALL, "50% off &&"));
    }

    @Test
    void plainTextIsUntouched() {
        assertEquals("hello world", ChatColorParser.toMiniMessage(ChatColorParser.DENY_ALL, "hello world"));
        assertEquals("i <3 this", ChatColorParser.toMiniMessage(ChatColorParser.DENY_ALL, "i <3 this"));
    }

    /** Angle brackets are everyday chat punctuation, so non-tags must reach the reader intact. */
    @Test
    void textThatOnlyLooksLikeMarkupIsPreserved() {
        for (String sample : new String[] { "i <3 this", "<lol> what", "use <div> tags", "a < b > c", "1 <2> 3" }) {
            assertEquals(sample, plain(ChatColorParser.parse(ChatColorParser.DENY_ALL, sample)), sample);
            assertEquals(sample, plain(ChatColorParser.parse(ChatColorParser.ALLOW_ALL, sample)), sample);
        }
    }

    /**
     * The channel manager hands us the chat component re-serialized to '&' codes, which is
     * how colours another listener already applied reach the parser.
     */
    @Test
    void stylingAppliedByAnotherListenerSurvivesTheRoundTrip() {
        LegacyComponentSerializer ampersand = LegacyComponentSerializer.builder()
                .character('&')
                .hexColors()
                .build();
        Component styled = Component.text("vip")
                .color(TextColor.fromHexString("#8C8F9B"))
                .decorate(TextDecoration.BOLD);

        Component reparsed = ChatColorParser.parse(ChatColorParser.ALLOW_ALL, ampersand.serialize(styled));

        assertEquals("vip", plain(reparsed));
        assertEquals(TextColor.fromHexString("#8C8F9B"), firstColor(reparsed));
        assertTrue(anyBold(reparsed));
    }

    @Test
    void stripFormattingRemovesBothSystems() {
        assertEquals("vip",
                ChatColorParser.stripFormatting("&#8C8F9B&lv&x&9&A&9&C&A&5i<gradient:red:blue>p</gradient>"));
    }

    @Test
    void formattingTokenLengthMeasuresEachForm() {
        assertEquals(2, ChatColorParser.formattingTokenLength("&dhi", 0));
        assertEquals(8, ChatColorParser.formattingTokenLength("&#8C8F9Bhi", 0));
        assertEquals(14, ChatColorParser.formattingTokenLength("&x&8&C&8&F&9&Bhi", 0));
        assertEquals(0, ChatColorParser.formattingTokenLength("&hi", 0));
        assertEquals(0, ChatColorParser.formattingTokenLength("hi", 0));
    }
}
