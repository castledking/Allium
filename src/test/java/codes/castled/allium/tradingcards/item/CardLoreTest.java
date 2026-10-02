package codes.castled.allium.tradingcards.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import java.util.List;
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

    private static CardLore lore(int bonusSlots, String separator) {
        return new CardLore(new CardLore.LoreData(
            BANDS,
            (id, level) -> "<green>" + id + " <white>+" + level + "</white>",
            "spawner heads",
            100,
            bonusSlots,
            separator,
            level -> 25.0));
    }

    private static TradingCardData card(Tier tier, int level, int quality, int rerolls,
                                        List<String> signatures, List<String> bonuses,
                                        double xp) {
        return new TradingCardData("AXOLOTL", "axolotl", tier, level, quality, signatures,
            bonuses, xp, rerolls, false);
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
    void rerollsUnlockAnotherSlotEachTime() {
        for (int rerolls = 0; rerolls < 5; rerolls++) {
            var slots = lore(5, "").bonusSlots(
                card(Tier.SIMPLE, 0, 15, rerolls, List.of("luck"), List.of(), 0.0));
            int locked = (int) slots.stream().filter(s -> s.contains("Locked")).count();
            assertEquals(5 - Math.min(5, rerolls + 1), locked,
                "at " + rerolls + " rerolls, " + locked + " slots should be locked");
        }
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
    void unlockedSlotsShowTheBonusesTheCardHolds() {
        var slots = lore(5, "").bonusSlots(
            card(Tier.SIMPLE, 4, 15, 2, List.of("luck"), List.of("armor", "haste"), 0.0));
        assertTrue(slots.get(0).contains("armor"));
        assertTrue(slots.get(1).contains("haste"));
        assertTrue(slots.get(2).contains("Empty!"), "a third reroll opens a third slot");
        assertTrue(slots.get(3).contains("Locked"));
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
            5, "", l -> 25.0));
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
            5, "", l -> 25.0));
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
        assertTrue(row.contains("<green></green>"), "no bar filled yet");
        assertTrue(row.contains("||||||||||||||||||||"), "but the empty bar is still drawn");
    }

    @Test
    void theSeparatorIsOmittedEntirelyWhenBlank() {
        var lines = lore(5, "").body(
            card(Tier.SIMPLE, 0, 15, 0, List.of("luck"), List.of(), 0.0), Tier.SIMPLE, 0.0);
        for (String line : lines) {
            assertFalse(line.contains("──"), "no separator characters when unset");
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
