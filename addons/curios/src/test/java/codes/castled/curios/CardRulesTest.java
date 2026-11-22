package codes.castled.curios;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CardRulesTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (var in = CardRulesTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void bundledConfigHasSfQualityBandsCurveMobColoursAndResourcePackDelivery() throws Exception {
        YamlConfiguration cfg = bundledConfig();
        assertEquals("allium", cfg.getString("resource-pack.delivery"));
        assertTrue(cfg.getBoolean("textured-menu"));
        CardRules rules = new CardRules(cfg);

        assertEquals(8, rules.qualityBands.size());
        assertEquals("rotten", rules.bandFor(1).id());
        assertEquals(1, rules.bandFor(1).heads());
        assertEquals("great", rules.bandFor(78).id());
        assertEquals(6, rules.bandFor(78).heads());
        assertEquals("emaculate", rules.bandFor(100).id());
        assertEquals(8, rules.bandFor(100).heads());

        assertEquals(25.0, rules.xpForLevel(0));
        assertEquals(120.0, rules.xpForLevel(10));
        assertEquals(600.0, rules.xpForLevel(25));
        assertEquals(2400.0, rules.xpForLevel(50));
        assertEquals(9000.0, rules.xpForLevel(75));

        assertEquals("aqua", rules.mobColour("allay"));
        assertEquals("dark_purple", rules.mobColour("witch"));
        assertEquals("dark_green", rules.mobColour("creeper"));
    }

    @Test
    void startingBoostsAreDeterministicAndScaleWithTierQualityAndLevel() throws Exception {
        CardRules rules = new CardRules(bundledConfig());
        List<CardRules.StartingValue> a = rules.startingValues("allay", CardRules.Tier.SIMPLE, 75, 10, CardRules.DEFAULT_SIGNATURES);
        List<CardRules.StartingValue> b = rules.startingValues("allay", CardRules.Tier.SIMPLE, 75, 10, CardRules.DEFAULT_SIGNATURES);
        assertEquals(a, b);
        assertEquals(3, a.size());
        assertEquals(10, a.stream().mapToInt(CardRules.StartingValue::points).sum());

        List<CardRules.StartingValue> fabled = rules.startingValues("allay", CardRules.Tier.FABLED, 100, 0, CardRules.DEFAULT_SIGNATURES);
        double fabledSum = fabled.stream().mapToDouble(CardRules.StartingValue::base).sum();
        double rottenSum = rules.startingValues("allay", CardRules.Tier.SIMPLE, 1, 0, CardRules.DEFAULT_SIGNATURES)
                .stream().mapToDouble(CardRules.StartingValue::base).sum();
        assertTrue(fabledSum > rottenSum);
    }

    @Test
    void bonusSlotsRoundTripAndRollWithinTierPool() throws Exception {
        CardRules rules = new CardRules(bundledConfig());
        List<CardRules.BonusSlotData> empty = CardRules.parseBonuses(",,,,");
        assertEquals(5, empty.size());
        assertTrue(empty.stream().allMatch(CardRules.BonusSlotData::isEmpty));

        CardRules.BonusSlotData rolled = rules.rollBonusSlot(CardRules.Tier.ELITE, empty, 0, new Random(42));
        assertNotNull(rolled);
        assertTrue(CardRules.BONUS_POOLS.get(CardRules.Tier.ELITE).contains(rolled.boostId()));
        assertTrue(rolled.strength() >= 1 && rolled.strength() <= 100);
        assertEquals(1, rolled.rolls());

        List<CardRules.BonusSlotData> encodedList = List.of(
                new CardRules.BonusSlotData(rolled.boostId(), rolled.strength(), true, 3),
                CardRules.BonusSlotData.EMPTY, CardRules.BonusSlotData.EMPTY,
                CardRules.BonusSlotData.EMPTY, CardRules.BonusSlotData.EMPTY);
        List<CardRules.BonusSlotData> decoded = CardRules.parseBonuses(CardRules.encodeBonuses(encodedList));
        assertEquals(encodedList.get(0), decoded.get(0));
    }

    @Test
    void bonusRollsFollowLegacysEscalatingPriceLadder() throws Exception {
        CardRules.RollPricing pricing = new CardRules(bundledConfig()).rollPricing;
        assertEquals(CardRules.RollPricing.LEGACY, pricing);
        double[] simple = {500, 700, 900, 1200, 1700, 2200, 3000, 4100};
        for (int rolls = 0; rolls < simple.length; rolls++) {
            assertEquals(simple[rolls], pricing.costFor(rolls, CardRules.Tier.SIMPLE), "roll #" + (rolls + 1));
        }
        assertEquals(800, pricing.costFor(0, CardRules.Tier.ELITE));
        assertEquals(2500, pricing.costFor(0, CardRules.Tier.FABLED));
        assertEquals(4600, pricing.costFor(2, CardRules.Tier.FABLED));
        // A config written before the section existed still prices rolls the legacy way.
        assertEquals(CardRules.RollPricing.LEGACY, new CardRules(new YamlConfiguration()).rollPricing);
    }

    @Test
    void rollPricingReadsItsSectionAndCostZeroIsFree() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("card-menu.bonus-slot-roll.cost", 1000);
        cfg.set("card-menu.bonus-slot-roll.escalation", 2.0);
        cfg.set("card-menu.bonus-slot-roll.round-to", 50);
        cfg.set("card-menu.bonus-slot-roll.tier-multipliers.FABLED", 10.0);
        CardRules.RollPricing pricing = new CardRules(cfg).rollPricing;
        assertEquals(4000, pricing.costFor(2, CardRules.Tier.SIMPLE));
        assertEquals(10_000, pricing.costFor(0, CardRules.Tier.FABLED));
        assertEquals(1500, pricing.costFor(0, CardRules.Tier.ELITE), "unset tiers keep legacy multipliers");
        cfg.set("card-menu.bonus-slot-roll.cost", 0);
        assertEquals(0, new CardRules(cfg).rollPricing.costFor(5, CardRules.Tier.FABLED));
    }

    @Test
    void heldCardBarShowsTheTitleInTheMobsColour() throws Exception {
        YamlConfiguration cfg = bundledConfig();
        CardRules rules = new CardRules(cfg);
        Catalog catalog = new Catalog(cfg);
        assertTrue(rules.heldActionBar);
        assertEquals("§6ғᴏx ᴛʀᴀᴅɪɴɢ ᴄᴀʀᴅ", HeldCardBar.title(catalog.entries.get("card_fox_elite"), rules));
        assertEquals("§8ᴡɪᴛʜᴇʀ ꜱᴋᴇʟᴇᴛᴏɴ ᴛʀᴀᴅɪɴɢ ᴄᴀʀᴅ", HeldCardBar.title(catalog.entries.get("card_wither_skeleton_simple"), rules));
        cfg.set("card-menu.held-card-action-bar", false);
        assertFalse(new CardRules(cfg).heldActionBar);
    }

    @Test
    void spentAndRollsSurviveEncodingAndClearing() {
        CardRules.BonusSlotData paid = new CardRules.BonusSlotData("armor", 60, false, 3, 2100.5);
        List<CardRules.BonusSlotData> slots = List.of(paid, paid.cleared(),
                CardRules.BonusSlotData.EMPTY, CardRules.BonusSlotData.EMPTY, CardRules.BonusSlotData.EMPTY);
        String encoded = CardRules.encodeBonuses(slots);
        assertEquals("armor:60:U:3:2100.5,:0:U:3:2100.5,,,", encoded);
        List<CardRules.BonusSlotData> decoded = CardRules.parseBonuses(encoded);
        assertEquals(paid, decoded.get(0));
        assertTrue(decoded.get(1).isEmpty());
        assertEquals(3, decoded.get(1).rolls(), "clearing never resets the next price");
        assertEquals(2100.5, decoded.get(1).spent());
        assertEquals(new CardRules.BonusSlotData("armor", 60, true, 3, 2600.5), paid.withLocked(true).paid(500));
        // Cards rolled before spend was tracked still read.
        assertEquals(new CardRules.BonusSlotData("armor", 60, true, 3, 0), CardRules.parseBonuses("armor:60:L:3,,,,").get(0));
    }

    @Test
    void mergeRulesRequireSameMobSameTierMaxLevelAndKeepHigherQuality() throws Exception {
        YamlConfiguration cfg = bundledConfig();
        Catalog catalog = new Catalog(cfg);
        CardRules rules = new CardRules(cfg);

        Catalog.Entry allaySimple = catalog.entries.get("card_allay_simple");
        Catalog.Entry allayElite = catalog.entries.get("card_allay_elite");
        Catalog.Entry allayFabled = catalog.entries.get("card_allay_fabled");
        Catalog.Entry beeSimple = catalog.entries.get("card_bee_simple");

        assertFalse(rules.checkMerge(allaySimple, 100, 50, beeSimple, 100, 80, catalog.entries).allowed());
        assertFalse(rules.checkMerge(allaySimple, 100, 50, allayElite, 100, 80, catalog.entries).allowed());
        assertFalse(rules.checkMerge(allaySimple, 99, 50, allaySimple, 100, 80, catalog.entries).allowed());
        assertFalse(rules.checkMerge(allayFabled, 100, 50, allayFabled, 100, 80, catalog.entries).allowed());

        CardRules.MergeVerdict ok = rules.checkMerge(allaySimple, 100, 42, allaySimple, 100, 88, catalog.entries);
        assertTrue(ok.allowed());
        assertEquals(CardRules.Tier.ELITE, ok.resultTier());
        assertEquals("card_allay_elite", ok.resultId());
        assertEquals(88, ok.mergedQuality());
    }

    @Test
    void smallCapsPreservesTagsAndCommands() {
        String formatted = CardRules.smallCaps("<gray>While equipped in </gray><aqua>/relics</aqua>");
        assertTrue(formatted.contains("<gray>"));
        assertTrue(formatted.contains("/relics"));
        assertTrue(formatted.contains("ᴡʜɪʟᴇ"));
    }
}
