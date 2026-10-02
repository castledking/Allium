package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

/**
 * Card lore: the tier presentation, and the band glyphs behind it.
 *
 * <p>Two things are being pinned down.
 *
 * <p>The tier is shown twice — as the {@code lb_*} badge near the top and as the
 * pip row further down — and they are read from one place. If they were ever
 * derived separately a mislabelled item could show a SIMPLE badge above an
 * ULTIMATE pip row, which is exactly what a player cannot interpret.
 *
 * <p>The bands are what make the background per-tier. Each line is wrapped in the
 * tier's own glyphs, so all five tiers get their own panel and the server is
 * what chooses between them.
 */
class CardLoreTest {

    // Colours are WRAPPED here, exactly as config.yml writes them. The previous
    // fixture used bare "red"/"gold", which hid the double-wrap bug: the test
    // passed while every real card rendered <>Emaculate</>.
    private static final List<QualityBand> BANDS = List.of(
        new QualityBand("damaged", 1, 25, 1, "<red>"),
        new QualityBand("pristine", 76, 100, 8, "<gold>"));

    private static final codes.castled.allium.tradingcards.boost.StartingBoosts.Rules
        STARTING_RULES = new codes.castled.allium.tradingcards.boost.StartingBoosts.Rules(1.0, 1);

    private static codes.castled.allium.tradingcards.boost.StartingBoosts startingBoosts() {
        return new codes.castled.allium.tradingcards.boost.StartingBoosts(BANDS, STARTING_RULES);
    }

    private static CardLore lore(int bonusSlots, String separator) {
        return new CardLore(new CardLore.LoreData(
            BANDS,
            (id, level) -> "<green>" + id + " <white>+" + level + "</white>",
            "spawner heads",
            100,
            bonusSlots,
            separator,
            level -> 25.0,
            mob -> "aqua",
            startingBoosts(),
            (id, value) -> id + " " + value,
            id -> id));
    }

    private static TradingCardData card(Tier tier, int level, int quality, int rerolls,
                                        List<String> signatures, List<String> bonuses,
                                        double xp) {
        return new TradingCardData("AXOLOTL", "axolotl", tier, level, quality, signatures,
            bonuses, xp, rerolls, false);
    }

    // ==================== title ====================

    @Test
    void theTitleIsTheFirstLoreLine() {
        // The item's own name is blank, because the tooltip's first line is the
        // name and the panel's ornament occupies the rows above the text. The
        // title therefore has to arrive as lore line 0 or the card shows nothing.
        var lines = lore(5, "---").render(card(Tier.FABLED, 3, 90, 0,
            List.of("luck"), List.of(), 12.0), 12.0);
        assertTrue(lines.get(0).contains("Axolotl Trading Card"),
            "line 0 should be the title, was: " + lines.get(0));
    }

    @Test
    void theTitleTakesTheMobsColour() {
        var lines = lore(5, "").render(card(Tier.SIMPLE, 1, 90, 0,
            List.of("luck"), List.of(), 0.0), 0.0);
        assertTrue(lines.get(0).endsWith("<aqua>Axolotl Trading Card</aqua>"),
            lines.get(0));
    }

    @Test
    void everyLoreSegmentCarriesAnExplicitColour() {
        // Regression: "<dark_gray>[<white>0</white>]</dark_gray>" leaves the
        // closing bracket with no colour of its own, relying on inheritance from
        // its parent. The client resolved that to vanilla's default lore colour
        // instead, so the bracket rendered dark purple on an otherwise grey line.
        var lines = lore(5, "---").render(card(Tier.FABLED, 12, 90, 2,
            List.of("luck", "might"), List.of("regeneration"), 40.0), 40.0);
        for (String line : lines) {
            collect(MiniMessage.miniMessage().deserialize(line), line);
        }
    }

    private static void collect(Component c, String source) {
        for (Component child : c.children()) {
            String text = child instanceof TextComponent t ? t.content() : "";
            if (!text.isBlank()) {
                TextColor colour = child.style().color();
                assertTrue(colour != null,
                    "segment '" + text + "' has no colour of its own and would "
                        + "fall back to the default lore colour, in: " + source);
            }
            collect(child, source);
        }
    }

    // ==================== pips ====================

    @Test
    void simpleHasExactlyOneFilledPip() {
        assertEquals("◆◇◇◇◇", Tier.SIMPLE.pips());
        assertEquals("◆◆◇◇◇", Tier.ELITE.pips());
        assertEquals("◆◆◆◇◇", Tier.ULTIMATE.pips());
        assertEquals("◆◆◆◆◇", Tier.LEGENDARY.pips());
        assertEquals("◆◆◆◆◆", Tier.FABLED.pips());
    }

    @Test
    void thePipRowSaysOneThroughFive() {
        assertEquals("◆◇◇◇◇  (1/5)", Tier.SIMPLE.pipsWithPosition());
        assertEquals("◆◆◆◆◆  (5/5)", Tier.FABLED.pipsWithPosition());
    }

    @Test
    void everyTierShowsFivePipsSoTheRowIsAConstantWidth() {
        for (Tier tier : Tier.values()) {
            assertEquals(5, tier.pips().length(),
                tier + " must render a full row, or the lore reflows per tier");
        }
    }

    // ==================== tier badge and row agree ====================

    @Test
    void theBadgeAndThePipRowComeFromTheSameTier() {
        for (Tier tier : Tier.values()) {
            var lines = lore(5, "").body(
                card(tier, 0, 15, 0, List.of("luck"), List.of(), 0.0), tier, 0.0);
            String badge = lines.get(0);
            String pipRow = lines.stream()
                .filter(l -> l.startsWith("<gray>Tier:</gray>"))
                .findFirst().orElseThrow();
            // Each pip is coloured individually now, so the raw glyph string is
            // no longer contiguous — count the filled ones instead.
            assertEquals(tier.ordinal() + 1,
                pipRow.split("<green>", -1).length - 1,
                tier + " should have " + (tier.ordinal() + 1) + " filled pips");
            assertEquals(Tier.values().length - tier.ordinal() - 1,
                pipRow.split("<#777777>", -1).length - 1,
                tier + " should have the rest hollow");
            // The tier badge leads the lore, with the name carried by the item
            // itself rather than printed again underneath it.
            assertEquals(CardLore.label(tier), plain(lines.get(0)).strip());
            // Forced white: a colour on the line tints the bitmap glyph and
            // washes out the texture colour it is drawn in.
            assertTrue(badge.startsWith("<white>") && badge.endsWith("</white>"),
                "the tier glyph must be forced to white: " + badge);
            assertTrue(pipRow.contains("[" + (tier.ordinal() + 1) + "/5]"),
                tier + " position should read " + (tier.ordinal() + 1) + "/5");
        }
    }

    @Test
    void eachTierHasItsOwnBadgeGlyph() {
        List<String> glyphs = java.util.Arrays.stream(Tier.values())
            .map(CardLore::label).toList();
        assertEquals(5, glyphs.size());
        assertEquals(5, new java.util.HashSet<>(glyphs).size(),
            "two tiers sharing a badge glyph is a card reading as the wrong tier");
        for (String glyph : glyphs) {
            assertFalse(glyph.isBlank(), "every tier needs a badge");
        }
    }



    // ==================== banding ====================




    /** The line with MiniMessage tags removed, so assertions read as a player sees it. */
    private static String plain(String line) {
        return line.replaceAll("<[^>]*>", "");
    }

    // ==================== bonus slots ====================

    @Test
    void aFreshCardShowsOneEmptySlotAndTheRestLocked() {
        var slots = lore(5, "").bonusSlots(
            card(Tier.SIMPLE, 0, 15, 0, List.of("luck"), List.of(), 0.0));
        assertEquals(5, slots.size());
        assertTrue(slots.get(0).contains("Empty!"), "one slot is open from the start");
        for (int i = 1; i < 5; i++) {
            assertTrue(slots.get(i).contains("Locked"),
                "slot " + (i + 1) + " should be locked, got " + slots.get(i));
        }
    }

    @Test
    void theTierDecidesHowManySlotsOpenAndRerollsDoNot() {
        // A slot is bought with its own roll now, so a reroll must not hand out a
        // free one — otherwise the reroll button undercuts the slot price.
        for (Tier tier : Tier.values()) {
            for (int rerolls = 0; rerolls < 5; rerolls++) {
                var slots = lore(5, "").bonusSlots(
                    card(tier, 0, 15, rerolls, List.of("luck"), List.of(), 0.0));
                int locked = (int) slots.stream().filter(s -> s.contains("Locked")).count();
                assertEquals(5 - Math.min(5, tier.ordinal() + 1), locked,
                    tier + " at " + rerolls + " rerolls should have the same "
                        + "locked slots, had " + locked);
            }
        }
    }

    @Test
    void aFabledCardOpensEverySlot() {
        var slots = lore(5, "").bonusSlots(
            card(Tier.FABLED, 0, 15, 0, List.of("luck"), List.of(), 0.0));
        assertEquals(5, slots.size());
        for (int i = 0; i < 5; i++) {
            assertTrue(slots.get(i).contains("Empty!"),
                "slot " + i + " should be rollable on a Fabled card, was " + slots.get(i));
        }
    }

    @Test
    void anUnrolledSlotIsGreyRatherThanRed() {
        // Grey reads as available; red read as a warning about something wrong.
        var slots = lore(5, "").bonusSlots(
            card(Tier.ELITE, 0, 15, 0, List.of("luck"), List.of(), 0.0));
        assertTrue(slots.get(0).contains("<#777777>Empty!</#777777>"),
            "slot 0 was " + slots.get(0));
        assertFalse(slots.get(0).contains("<red>"));
    }

    @Test
    void thereAreFiveSlotsNotThree() {
        // boosts.yml roll.count is 3, but the card shows five so a player can see
        // what is still locked. Deriving the slot count from the roll count is
        // what made only three appear.
        assertEquals(5, lore(5, "").bonusSlots(
            card(Tier.SIMPLE, 0, 15, 0, List.of("luck"), List.of(), 0.0)).size());
    }

    @Test
    void rolledSlotsShowTheBonusesTheCardHolds() {
        var slots = lore(5, "").bonusSlots(
            card(Tier.LEGENDARY, 4, 15, 0, List.of("luck"), List.of("armor", "haste"), 0.0));
        assertTrue(slots.get(0).contains("armor"));
        assertTrue(slots.get(1).contains("haste"));
        // LEGENDARY opens four slots, so the next two are rollable and the
        // fifth is not yet.
        assertTrue(slots.get(2).contains("Empty!"));
        assertTrue(slots.get(3).contains("Empty!"));
        assertTrue(slots.get(4).contains("Locked"));
    }

    @Test
    void theSeparatorIsCapturedNotReadLive() {
        // LoreData takes the separator as a value, so a renderer built at startup
        // keeps drawing the old rule no matter what config.yml says afterwards.
        // That is why a reload has to rebuild the renderer before re-rendering
        // anything; without it, editing lore-separator looks like a reload that
        // did nothing.
        // Letters, not rules: the XP bar is a run of the same box-drawing
        // character as lore-separator, so a rule separator would be found
        // inside the bar and the comparison would pass for the wrong reason.
        var narrow = lore(5, "AA").render(
            card(Tier.SIMPLE, 0, 90, 0, List.of("luck"), List.of(), 0.0), 0.0);
        var wide = lore(5, "BBBB").render(
            card(Tier.SIMPLE, 0, 90, 0, List.of("luck"), List.of(), 0.0), 0.0);
        assertFalse(narrow.equals(wide),
            "two renderers built from different separators rendered identically");
        assertTrue(wide.stream().anyMatch(l -> l.contains("BBBB")));
        assertFalse(narrow.stream().anyMatch(l -> l.contains("BBBB")));
    }

    @Test
    void aFreshCardShowsNoBonusesAtAll() {
        // Drops carry no bonuses now: a slot is bought with its own money, so a
        // drop that handed one over made the price a suggestion. This is the
        // observable half of that — the drop path itself needs a Bukkit mock.
        var slots = lore(5, "").bonusSlots(
            card(Tier.FABLED, 0, 90, 0, List.of("luck"), List.of(), 0.0));
        for (String slot : slots) {
            assertFalse(slot.contains("luck"),
                "a card that was never rolled must not show a bonus, was " + slot);
        }
        assertEquals(5, slots.size());
        assertEquals(5, slots.stream().filter(s -> s.contains("Empty!")).count(),
            "a Fabled card opens every slot to roll, got " + slots);
    }

    @Test
    void aFilledSlotHasNoPrefixAndAnEmptyOneDoes() {
        // "Crop Yield +15%" names itself. The prefix only ever labelled the slots
        // that were empty or locked, and on a full card it read as five pending
        // rolls rather than five bonuses.
        var slots = lore(5, "").bonusSlots(
            card(Tier.FABLED, 0, 90, 0, List.of("luck"), List.of("crop_yield"), 0.0));
        assertFalse(slots.get(0).contains("Bonus:"),
            "a filled slot should not be prefixed, was " + slots.get(0));
        assertTrue(slots.get(0).contains("crop_yield"));
        assertTrue(slots.get(1).contains("Bonus:"),
            "an empty slot still needs labelling, was " + slots.get(1));
    }

    @Test
    void thereIsAlwaysAtLeastOneSlot() {
        var slots = lore(0, "").bonusSlots(
            card(Tier.SIMPLE, 0, 15, 0, List.of("luck"), List.of(), 0.0));
        assertEquals(1, slots.size(), "a card showing no bonus slots reads as a bug");
    }

    // ==================== body layout ====================

    @Test
    void aFullCardCarriesEverySectionTheDesignCallsFor() {
        var lines = lore(5, "---").body(
            card(Tier.SIMPLE, 0, 15, 0, List.of("luck", "strength", "tokens"),
                List.of(), 0.0), Tier.SIMPLE, 0.0);
        String all = String.join("\n", lines);
        assertTrue(all.contains("Level:"), "level row");
        assertTrue(all.contains("While equipped in"), "the /reliques header");
        assertTrue(all.contains("luck"), "signatures are listed");
        assertTrue(all.contains("Quality:"), "quality row");
        assertTrue(plain(all).contains("Damaged"), "quality band name comes from the config");
        assertTrue(all.contains("[15%]"), "quality reads as a percentage");
        assertTrue(all.contains("Tier:"), "tier row");
        assertTrue(all.contains("Right-Click to Open Menu"), "the menu hint");
    }

    @Test
    void theQualityBandComesFromTheConfigAndIsNotDoubleWrapped() {
        var lines = lore(5, "").body(
            card(Tier.SIMPLE, 0, 90, 0, List.of("luck"), List.of(), 0.0), Tier.SIMPLE, 0.0);
        String all = String.join("\n", lines);
        assertTrue(plain(all).contains("Pristine"), "90 is in the pristine band");
        // config.yml stores the colour already wrapped, as colour: "<gold>".
        // Wrapping it again emitted <<gold>>, which MiniMessage renders as gold
        // text between two literal angle brackets — exactly what was reported.
        assertFalse(all.contains("<<"), "the band colour must be used verbatim: " + all);
        assertFalse(plain(all).contains("<"), "no raw tag characters should survive");
    }

    @Test
    void aBareColourInConfigIsStillAccepted() {
        // An operator writing colour: gold rather than colour: "<gold>" should
        // not get a tag wrapped around a tag.
        var bands = List.of(new QualityBand("mint", 86, 99, 7, "aqua"));
        var lore = new CardLore(new CardLore.LoreData(bands, (id, l) -> id, "heads", 100,
            5, "", l -> 25.0, mob -> "aqua", startingBoosts(),
            (id, value) -> id + " " + value, id -> id));
        var lines = lore.body(card(Tier.SIMPLE, 0, 90, 0, List.of("luck"), List.of(), 0.0),
            Tier.SIMPLE, 0.0);
        String quality = lines.stream().filter(l -> l.contains("Quality:"))
            .findFirst().orElseThrow();
        assertFalse(quality.contains("<<"), "bare colour must be wrapped once: " + quality);
        assertFalse(plain(quality).contains("<"), quality);
    }

    @Test
    void aHexColourInConfigIsAccepted() {
        var bands = List.of(new QualityBand("mint", 86, 99, 7, "#777777"));
        var lore = new CardLore(new CardLore.LoreData(bands, (id, l) -> id, "heads", 100,
            5, "", l -> 25.0, mob -> "aqua", startingBoosts(),
            (id, value) -> id + " " + value, id -> id));
        var lines = lore.body(card(Tier.SIMPLE, 0, 90, 0, List.of("luck"), List.of(), 0.0),
            Tier.SIMPLE, 0.0);
        String quality = lines.stream().filter(l -> l.contains("Quality:"))
            .findFirst().orElseThrow();
        assertTrue(quality.contains("<color:#777777>"), "hex needs the colour tag: " + quality);
    }

    @Test
    void theProgressRowAlwaysShowsSoTheCardLooksTheSameBeforeAndAfter() {
        // The design shows 0/25 XP on a fresh card, so the row is always there.
        // Hiding it would make a card's layout change the first time it earned
        // anything, which reads as the card being a different item.
        var none = lore(5, "").body(
            card(Tier.SIMPLE, 0, 15, 0, List.of("luck"), List.of(), 0.0), Tier.SIMPLE, 0.0);
        assertTrue(plain(String.join("\n", none)).contains("0/25 XP"),
            "got: " + plain(String.join("\n", none)));

        var some = lore(5, "").body(
            card(Tier.SIMPLE, 0, 15, 0, List.of("luck"), List.of(), 10.0), Tier.SIMPLE, 10.0);
        assertTrue(plain(String.join("\n", some)).contains("10/25 XP"),
            "got: " + plain(String.join("\n", some)));
    }

    @Test
    void theProgressBarIsEmptyButPresentAtZero() {
        var none = lore(5, "").body(
            card(Tier.SIMPLE, 0, 15, 0, List.of("luck"), List.of(), 0.0), Tier.SIMPLE, 0.0);
        String row = String.join("\n", none);
        assertTrue(row.contains("<green><strikethrough></strikethrough></green>"),
            "no bar filled yet, was: " + row);
        assertTrue(row.contains("<dark_gray><strikethrough>" + "\u2500".repeat(35)
                + "</strikethrough></dark_gray>"),
            "but the empty bar is still drawn, was: " + row);
    }

    @Test
    void theSeparatorIsOmittedEntirelyWhenBlank() {
        // The XP row legitimately draws the same box-drawing character, so it is
        // excluded here: what this pins is that no *bare* rule line appears.
        var lines = lore(5, "").body(
            card(Tier.SIMPLE, 0, 15, 0, List.of("luck"), List.of(), 0.0), Tier.SIMPLE, 0.0);
        for (String line : lines) {
            if (line.contains("<strikethrough>")) {
                continue;
            }
            assertFalse(line.contains("\u2500"), "no separator characters when unset: " + line);
        }
    }

    @Test
    void aCardWithNoSignaturesSaysSoRatherThanShowingAnEmptyGap() {
        var lines = lore(5, "").body(
            card(Tier.SIMPLE, 0, 15, 0, List.of(), List.of(), 0.0), Tier.SIMPLE, 0.0);
        assertTrue(String.join("\n", lines).contains("None"));
    }

    @Test
    void aNullCardRendersToNothingRatherThanThrowing() {
        assertEquals(List.of(), lore(5, "").render(null, 0.0));
    }
}
