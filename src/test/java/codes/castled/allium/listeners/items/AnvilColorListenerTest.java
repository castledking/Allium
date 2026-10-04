package codes.castled.allium.listeners.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.util.Set;

import codes.castled.allium.managers.chat.ChatColorParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anvil renaming, which runs the chat parser under the {@code allium.anvil} scope. The cases here
 * cover what {@link AnvilColorListener} hands to the result item, and specifically the ways the
 * public anvil-colour plugins fall over: their legacy gate is a case-sensitive lowercase regex, so
 * {@code &C}, {@code &L} and {@code &#RRGGBB} never match it and reach the item as literal text,
 * while only their {@code <#hex></#hex>} gradient form is recognised as markup.
 */
class AnvilColorListenerTest {

    private static final String ANVIL = "allium.anvil";

    private static ChatColorParser.PermissionCheck granting(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return granted::contains;
    }

    /** The nodes a player needs for every legacy {@code &a} style code and every tag. */
    private static ChatColorParser.PermissionCheck allFormatting() {
        return granting(ANVIL + ".color.*", ANVIL + ".format.*", ANVIL + ".minimessage.*");
    }

    private static Component parse(ChatColorParser.PermissionCheck permissions, String raw) {
        return ChatColorParser.parse(permissions, raw, ANVIL);
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

    private static boolean hasDecoration(Component component, TextDecoration decoration) {
        if (component.decoration(decoration) == TextDecoration.State.TRUE) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasDecoration(child, decoration)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void legacyColourCodeIsApplied() {
        assertEquals(NamedTextColor.RED, firstColor(parse(granting(ANVIL + ".color.red"), "&cSword")));
    }

    @Test
    void uppercaseLegacyCodesAreAppliedToo() {
        // The gate in the reference plugins is "[0-9a-fk-or]" with no case flag, so &C and &L
        // fall through to raw text there.
        assertEquals(NamedTextColor.RED, firstColor(parse(granting(ANVIL + ".color.red"), "&CSword")));
        assertTrue(hasDecoration(parse(granting(ANVIL + ".format.bold"), "&LBold"), TextDecoration.BOLD));
    }

    @Test
    void hexColourCodeIsApplied() {
        assertEquals(TextColor.fromHexString("#8C8F9B"),
                firstColor(parse(granting(ANVIL + ".color.hex"), "&#8C8F9BVip")));
        assertEquals(TextColor.fromHexString("#FF0000"),
                firstColor(parse(granting(ANVIL + ".color.hex"), "&x&F&F&0&0&0&0Sale")));
    }

    @Test
    void colourCodeWithoutPermissionIsDropped() {
        Component parsed = parse(granting(), "&cSword");
        assertEquals("Sword", plain(parsed));
        assertNull(firstColor(parsed));
    }

    @Test
    void hexCodeWithoutPermissionIsDropped() {
        Component parsed = parse(granting(ANVIL + ".color.red"), "&#8C8F9BVip");
        assertNull(firstColor(parsed));
        assertFalse(plain(parsed).isEmpty());
    }

    @Test
    void miniMessageGradientIsApplied() {
        // The granular tag gates only apply once the surface-level node is held, same as chat.
        ChatColorParser.PermissionCheck permissions =
                granting(ANVIL + ".minimessage", ANVIL + ".minimessage.gradient");
        Component parsed = parse(permissions, "<gradient:#FF0000:#00FFFF>Sale</gradient>");
        assertEquals("Sale", plain(parsed));
        assertEquals(TextColor.fromHexString("#FF0000"), firstColor(parsed));
        assertNotNull(firstColor(parsed), "a gradient must colour the name");
    }

    @Test
    void miniMessageGradientNeedsItsOwnNode() {
        Component parsed = parse(granting(ANVIL + ".minimessage"), "<gradient:#FF0000:#00FFFF>Sale</gradient>");
        assertEquals("Sale", plain(parsed));
        assertNull(firstColor(parsed), "holding allium.anvil.minimessage alone must not unlock gradient");
    }

    @Test
    void miniMessageTagsAreDroppedWithoutPermission() {
        Component parsed = parse(granting(ANVIL + ".color.red"), "<gradient:#FF0000:#00FFFF>Sale</gradient>");
        assertEquals("Sale", plain(parsed));
        assertNull(firstColor(parsed));
    }

    @Test
    void legacyAndMiniMessageMixInOneName() {
        Component parsed = parse(allFormatting(), "&#8C8F9B&lVIP <gradient:#FF0000:#00FFFF>Sale</gradient>");
        assertEquals("VIP Sale", plain(parsed));
        assertEquals(TextColor.fromHexString("#8C8F9B"), firstColor(parsed));
        assertTrue(hasDecoration(parsed, TextDecoration.BOLD));
    }

    @Test
    void literalAmpersandSurvives() {
        assertEquals("Tom & Jerry", plain(parse(allFormatting(), "Tom & Jerry")));
        assertFalse(ChatColorParser.containsFormatting("Tom & Jerry"),
                "a lone ampersand must not count as formatting, or the listener would rename the item");
    }

    @Test
    void plainNameNeedsNoWork() {
        assertFalse(ChatColorParser.containsFormatting("Sword of Testing"));
        assertFalse(ChatColorParser.containsFormatting(""));
        assertFalse(ChatColorParser.containsFormatting(null));
    }

    @Test
    void permissionsDoNotLeakBetweenSurfaces() {
        // An anvil grant must not colour signs or chat, and a sign grant must not colour an anvil.
        assertNull(firstColor(ChatColorParser.parse(granting(ANVIL + ".color.red"), "&cSword", "allium.sign")));
        assertNull(firstColor(ChatColorParser.parse(granting("allium.sign.color.red"), "&cSword", ANVIL)));
    }

    @Test
    void typedTextIsTheSource() {
        assertEquals("&cSword", AnvilColorListener.sourceText("&cSword", "&9Result", "Diamond Sword"));
        assertEquals("<red>Sword</red>",
                AnvilColorListener.sourceText("<red>Sword</red>", "&9Result", "Diamond Sword"));
    }

    @Test
    void resultNameIsTheSourceWhenRenameTextIsUnavailable() {
        // getRenameText() returns null on a wrapped or modded anvil, so the name vanilla already
        // applied to the result slot is all we get - and it has to be enough.
        assertEquals("&cSword", AnvilColorListener.sourceText(null, "&cSword", "Diamond Sword"));
        assertEquals("&cSword", AnvilColorListener.sourceText("", "&cSword", "Diamond Sword"));
        assertEquals("&cSword", AnvilColorListener.sourceText("Sword", "&cSword", "Diamond Sword"));
        // An input item with no name of its own still counts as renamed once the result has one.
        assertEquals("&cSword", AnvilColorListener.sourceText("", "&cSword", null));
    }

    @Test
    void unrenamedResultIsLeftAlone() {
        // Equal names mean no rename happened, so another plugin's name must never be re-parsed.
        assertEquals("", AnvilColorListener.sourceText(null, "&cSword", "&cSword"));
        assertEquals("", AnvilColorListener.sourceText(null, "Sword", "Sword"));
        assertEquals("", AnvilColorListener.sourceText(null, null, "Sword"));
        assertEquals("", AnvilColorListener.sourceText(null, null, null));
    }

    @Test
    void colouredNameIsNotItalic() {
        Component name = AnvilColorListener.forItemName(parse(granting(ANVIL + ".color.red"), "&cSword"), "&cSword");
        assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC));
    }

    @Test
    void requestedItalicsSurvive() {
        // A simple name is a single component carrying both colour and italics, so the flag has
        // to leave it alone rather than outrank it.
        Component legacy = AnvilColorListener.forItemName(parse(allFormatting(), "&c&oSword"), "&c&oSword");
        assertEquals(TextDecoration.State.TRUE, legacy.decoration(TextDecoration.ITALIC));

        Component tagged = AnvilColorListener.forItemName(
                parse(granting(ANVIL + ".color.red", ANVIL + ".minimessage", ANVIL + ".minimessage.format"),
                        "&c<italic>Sword</italic>"),
                "&c<italic>Sword</italic>");
        assertTrue(hasDecoration(tagged, TextDecoration.ITALIC));
    }

    @Test
    void italicsSurviveOnOnePartOfANameOnly() {
        Component mixed = AnvilColorListener.forItemName(
                parse(allFormatting(), "&cSword<italic>!</italic>"), "&cSword<italic>!</italic>");
        assertEquals(TextDecoration.State.FALSE, mixed.decoration(TextDecoration.ITALIC));
        assertTrue(hasDecoration(mixed, TextDecoration.ITALIC), "the <italic> run stays slanted");
    }

    @Test
    void uncolouredNameKeepsVanillaItalics() {
        // Only a colour turns the slant off, so a plain or format-only name looks untouched.
        Component bold = AnvilColorListener.forItemName(parse(granting(ANVIL + ".format.bold"), "&lSword"), "&lSword");
        assertEquals(TextDecoration.State.NOT_SET, bold.decoration(TextDecoration.ITALIC));
        Component plain = AnvilColorListener.forItemName(Component.text("Sword"), "Sword");
        assertEquals(TextDecoration.State.NOT_SET, plain.decoration(TextDecoration.ITALIC));
    }

    @Test
    void unitalicTagLandsAfterResetsSoALaterColourCannotWipeIt() {
        // A reset wipes anything before it, so the tag has to go in after the ones we emit.
        assertEquals("<reset><!italic><red>test", AnvilColorListener.unitalicSource("<reset><red>test"));
        assertEquals("<!italic><red>test", AnvilColorListener.unitalicSource("<red>test"));
        assertEquals("<!italic><gradient:#FF0000:#00FFFF>Sale</gradient>",
                AnvilColorListener.unitalicSource("<gradient:#FF0000:#00FFFF>Sale</gradient>"));
    }

    @Test
    void unitalicTagStillYieldsToRequestedItalics() {
        String filtered = ChatColorParser.toMiniMessage(allFormatting(), "&c&otest", ANVIL);
        Component name = ChatColorParser.deserialize(AnvilColorListener.unitalicSource(filtered));
        assertEquals(TextDecoration.State.TRUE, name.decoration(TextDecoration.ITALIC));
    }

    @Test
    void unitalicTagRemovesTheSlantEndToEnd() {
        String filtered = ChatColorParser.toMiniMessage(allFormatting(), "<red>test", ANVIL);
        assertEquals("<red>test", filtered);
        Component name = ChatColorParser.deserialize(AnvilColorListener.unitalicSource(filtered));
        assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC));
        assertEquals(NamedTextColor.RED, name.color());
    }

    @Test
    void unfilteredCodesKeepVanillaItalics() {
        // No colour survives the permission pass, so the name must not be un-slanted either.
        String filtered = ChatColorParser.toMiniMessage(granting(), "&ctest", ANVIL);
        assertFalse(ChatColorParser.containsColor(filtered));
        Component name = ChatColorParser.deserialize(filtered);
        assertEquals(TextDecoration.State.NOT_SET, name.decoration(TextDecoration.ITALIC));
    }

    @Test
    void colourDetectionCoversBothSyntaxes() {
        assertTrue(ChatColorParser.containsColor("&cSword"));
        assertTrue(ChatColorParser.containsColor("&CSword"));
        assertTrue(ChatColorParser.containsColor("&#8C8F9BVip"));
        assertTrue(ChatColorParser.containsColor("&x&F&F&0&0&0&0Sale"));
        assertTrue(ChatColorParser.containsColor("<red>Sword</red>"));
        assertTrue(ChatColorParser.containsColor("<#FF0000>Sword</#FF0000>"));
        assertTrue(ChatColorParser.containsColor("<gradient:#FF0000:#00FFFF>Sale</gradient>"));
        assertTrue(ChatColorParser.containsColor("<rainbow>Sale</rainbow>"));

        assertFalse(ChatColorParser.containsColor("&lBold"));
        assertFalse(ChatColorParser.containsColor("&oItalic"));
        assertFalse(ChatColorParser.containsColor("&rReset"));
        assertFalse(ChatColorParser.containsColor("<bold>Sword</bold>"));
        assertFalse(ChatColorParser.containsColor("Tom & Jerry"));
        assertFalse(ChatColorParser.containsColor("Sword"));
        assertFalse(ChatColorParser.containsColor(""));
        assertFalse(ChatColorParser.containsColor(null));
    }
}