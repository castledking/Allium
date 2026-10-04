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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sign colouring, which runs the chat parser under the {@code allium.sign} scope. The cases here
 * mirror {@link ChatColorParserTest} because signs regressed separately from chat: legacy codes
 * were only accepted with the exact per-colour node, hex codes lost their digits, a literal
 * ampersand was swallowed and bracketed prose was deleted.
 */
class SignColorParserTest {

    private static final String SIGN = "allium.sign";

    private static ChatColorParser.PermissionCheck granting(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return granted::contains;
    }

    /** The colour nodes a player needs for every legacy {@code &a} style code to work. */
    private static ChatColorParser.PermissionCheck signColours() {
        return granting(SIGN + ".color.*", SIGN + ".format.*");
    }

    private static String mini(ChatColorParser.PermissionCheck permissions, String raw) {
        return ChatColorParser.toMiniMessage(permissions, raw, SIGN);
    }

    private static Component parse(ChatColorParser.PermissionCheck permissions, String raw) {
        return ChatColorParser.parse(permissions, raw, SIGN);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

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
        return component.children().stream().anyMatch(SignColorParserTest::anyBold);
    }

    @Test
    void ampersandColourCodeIsApplied() {
        Component parsed = parse(granting(SIGN + ".color.red"), "&cShop");

        assertEquals("Shop", plain(parsed));
        assertEquals(NamedTextColor.RED, firstColor(parsed));
    }

    @Test
    void sectionSignColourCodeIsAppliedToo() {
        assertEquals(NamedTextColor.RED, firstColor(parse(signColours(), "§cShop")));
    }

    /** The wildcard the plugin declares has to reach every colour, not just be documentation. */
    @Test
    void wildcardGrantsEveryLegacyColour() {
        Component parsed = parse(signColours(), "&cred &9blue");

        assertEquals("red blue", plain(parsed));
        assertEquals(NamedTextColor.RED, firstColor(parsed));
        assertEquals(2, countColours(parsed));
    }

    /** Hex used to lose its '#'-prefixed digits and leave "FF0000" printed on the sign. */
    @Test
    void hexColourKeepsItsDigitsOffTheSign() {
        ChatColorParser.PermissionCheck withHex = granting(SIGN + ".color.hex");

        assertEquals("<reset><#FF0000>Sale", mini(withHex, "&#FF0000Sale"));
        Component parsed = parse(withHex, "&#FF0000Sale");
        assertEquals("Sale", plain(parsed));
        assertEquals(TextColor.fromHexString("#FF0000"), firstColor(parsed));
    }

    @Test
    void spigotXHexFormIsUnderstood() {
        assertEquals("<reset><#8C8F9B>hi", mini(granting(SIGN + ".color.hex"), "&x&8&C&8&F&9&Bhi"));
    }

    @Test
    void hexNeedsItsOwnPermission() {
        ChatColorParser.PermissionCheck oneColour = granting(SIGN + ".color.red");

        assertEquals("Sale", mini(oneColour, "&#FF0000Sale"));
    }

    @Test
    void formatCodesAndColoursMixOnOneLine() {
        Component parsed = parse(granting(SIGN + ".color.hex", SIGN + ".format.*"), "&#8C8F9B&lvip");

        assertEquals("vip", plain(parsed));
        assertEquals(TextColor.fromHexString("#8C8F9B"), firstColor(parsed));
        assertTrue(anyBold(parsed));
    }

    /** "Tom & Jerry" lost its ampersand to the colour scanner. */
    @Test
    void literalAmpersandIsKept() {
        assertEquals("Tom & Jerry", mini(signColours(), "Tom & Jerry"));
        assertEquals("50% off &&", mini(signColours(), "50% off &&"));
    }

    /** Bracketed prose is everyday sign text; the old tag matcher deleted it. */
    @Test
    void textThatOnlyLooksLikeMarkupIsPreserved() {
        for (String sample : new String[] { "i <3 this", "<lol> what", "use <div> tags", "a < b > c", "1 <2> 3" }) {
            assertEquals(sample, plain(parse(ChatColorParser.DENY_ALL, sample)), sample);
            assertEquals(sample, plain(parse(signColours(), sample)), sample);
        }
    }

    @Test
    void codesAreDroppedWithoutPermission() {
        Component parsed = parse(ChatColorParser.DENY_ALL, "&#8C8F9B&lvip");

        assertEquals("vip", plain(parsed));
        assertNull(firstColor(parsed));
        assertFalse(anyBold(parsed));
    }

    @Test
    void perColourPermissionsAreHonoured() {
        ChatColorParser.PermissionCheck onlyRed = granting(SIGN + ".color.red");

        assertEquals("<reset><red>red then blue", mini(onlyRed, "&cred then &9blue"));
    }

    @Test
    void obfuscatedAnswersToEitherSpelling() {
        assertEquals("<obfuscated>hi", mini(granting(SIGN + ".format.magic"), "&khi"));
        assertEquals("<obfuscated>hi", mini(granting(SIGN + ".format.obfuscated"), "&khi"));
    }

    @Test
    void miniMessageTagsNeedTheirOwnPermissions() {
        ChatColorParser.PermissionCheck legacyOnly = granting(SIGN + ".color.*");

        assertEquals("<reset><light_purple>hello world",
                mini(legacyOnly, "&dhello <rainbow>world</rainbow>"));

        ChatColorParser.PermissionCheck withTags = granting(SIGN + ".color.*", SIGN + ".minimessage.*");
        assertEquals("<reset><light_purple>hello <rainbow>world</rainbow>",
                mini(withTags, "&dhello <rainbow>world</rainbow>"));
    }

    /** Signs and chat must not share an accident: chat permissions must not colour a sign. */
    @Test
    void chatPermissionsDoNotReachSigns() {
        assertEquals("hi", mini(granting("chat.color.*", "chat.format.*"), "&chi"));
    }

    /** Styling another plugin already applied has to survive being read back as '&' codes. */
    @Test
    void stylingAppliedByAnotherPluginSurvives() {
        LegacyComponentSerializer ampersand = LegacyComponentSerializer.builder()
                .character('&')
                .hexColors()
                .build();
        Component styled = Component.text("vip")
                .color(TextColor.fromHexString("#8C8F9B"))
                .decorate(TextDecoration.BOLD);

        Component reparsed = parse(granting(SIGN + ".color.hex", SIGN + ".format.*"),
                ampersand.serialize(styled));

        assertEquals("vip", plain(reparsed));
        assertEquals(TextColor.fromHexString("#8C8F9B"), firstColor(reparsed));
        assertTrue(anyBold(reparsed));
    }

    private static int countColours(Component component) {
        int count = component.color() != null ? 1 : 0;
        for (Component child : component.children()) {
            count += countColours(child);
        }
        return count;
    }
}