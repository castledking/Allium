package codes.castled.allium.tool.disarmer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DisarmerPolicyTest {

    private static final DisarmerRules RULES = new DisarmerRules(3, 120_000L, 0.03D, 0.05D);

    @Test
    void missingOrUnreadableStateIsFullAndValuesAreClamped() {
        assertEquals(DisarmerCharges.full(3), DisarmerCharges.fromState(Map.of(), 3));
        assertEquals(3, DisarmerCharges.fromState(Map.of("charges", "abc"), 3).charges());
        assertEquals(3, DisarmerCharges.fromState(Map.of("charges", "9"), 3).charges());
        assertEquals(0, DisarmerCharges.fromState(Map.of("charges", "-4"), 3).charges());
        DisarmerCharges parsed = DisarmerCharges.fromState(
                Map.of("charges", "1", "recharge_at", "500", "broken", "TRUE", "uses", "7"), 3);
        assertEquals(new DisarmerCharges(1, 500L, true, 7L), parsed);
        assertEquals(parsed, DisarmerCharges.fromState(parsed.toState(), 3));
    }

    @Test
    void eachSuccessfulDisarmSpendsExactlyOneChargeAndTheLastStartsOneRecharge() {
        DisarmerCharges state = DisarmerCharges.full(3);
        long now = 1_000L;
        state = state.consume(now, RULES, 0.99D).next();
        assertEquals(new DisarmerCharges(2, 0L, false, 1L), state);
        state = state.consume(now, RULES, 0.99D).next();
        assertEquals(new DisarmerCharges(1, 0L, false, 2L), state);
        state = state.consume(now, RULES, 0.99D).next();
        assertEquals(new DisarmerCharges(0, 121_000L, false, 3L), state);

        DisarmerCharges spent = state;
        assertThrows(IllegalStateException.class, () -> spent.consume(now, RULES, 0.99D));
        assertEquals(DisarmerCharges.Availability.Kind.RECHARGING, state.availability(now).kind());
        assertEquals(120_000L, state.availability(now).remainingMillis());
    }

    @Test
    void rechargeRefillsCompletelyOnlyAfterTheTimerAndNeverForBrokenTools() {
        DisarmerCharges spent = new DisarmerCharges(0, 121_000L, false, 3L);
        assertSame(spent, spent.settle(120_999L, 3));
        assertEquals(new DisarmerCharges(3, 0L, false, 3L), spent.settle(121_000L, 3));
        // A spent tool without a timer (e.g. an edited or imported stack) refills immediately.
        assertEquals(3, new DisarmerCharges(0, 0L, false, 0L).settle(0L, 3).charges());
        // Partially used tools never refill on their own.
        DisarmerCharges partial = new DisarmerCharges(1, 0L, false, 2L);
        assertSame(partial, partial.settle(Long.MAX_VALUE, 3));
        DisarmerCharges broken = new DisarmerCharges(0, 0L, true, 5L);
        assertSame(broken, broken.settle(Long.MAX_VALUE, 3));
        assertEquals(DisarmerCharges.Availability.Kind.BROKEN, broken.availability(0L).kind());
        assertThrows(IllegalStateException.class, () -> broken.consume(0L, RULES, 0.99D));
    }

    @Test
    void breakChanceUsesTheFinalChanceOnlyForTheLastChargeAndRollsOnce() {
        assertEquals(0.03D, RULES.breakChance(3));
        assertEquals(0.03D, RULES.breakChance(2));
        assertEquals(0.05D, RULES.breakChance(1));

        DisarmerCharges.Consumption early = DisarmerCharges.full(3).consume(0L, RULES, 0.0299D);
        assertTrue(early.broke());
        assertEquals(new DisarmerCharges(0, 0L, true, 1L), early.next());
        assertFalse(DisarmerCharges.full(3).consume(0L, RULES, 0.03D).broke());

        DisarmerCharges last = new DisarmerCharges(1, 0L, false, 2L);
        assertTrue(last.consume(0L, RULES, 0.0499D).broke());
        assertFalse(last.consume(0L, RULES, 0.05D).broke());
        assertFalse(DisarmerCharges.full(3).consume(0L, DisarmerRules.NO_BREAK, 0.0D).broke());
    }

    @Test
    void bypassRecordsTheUseWithoutSpendingOrBreaking() {
        DisarmerCharges state = new DisarmerCharges(1, 0L, false, 4L);
        assertEquals(new DisarmerCharges(1, 0L, false, 5L), state.recordBypassUse());
        assertEquals(Long.MAX_VALUE, new DisarmerCharges(1, 0L, false, Long.MAX_VALUE).recordBypassUse().uses());
        assertEquals(Long.MAX_VALUE, new DisarmerCharges(1, 0L, false, Long.MAX_VALUE).consume(0L, RULES, 1.0D).next().uses());
        assertEquals(Long.MAX_VALUE, new DisarmerCharges(1, 0L, false, 0L)
                .consume(Long.MAX_VALUE - 1L, RULES, 1.0D).next().rechargeAt());
    }

    @Test
    void rulesRejectInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new DisarmerRules(0, 1L, 0D, 0D));
        assertThrows(IllegalArgumentException.class, () -> new DisarmerRules(3, -1L, 0D, 0D));
        assertThrows(IllegalArgumentException.class, () -> new DisarmerRules(3, 1L, 1.5D, 0D));
        assertThrows(IllegalArgumentException.class, () -> new DisarmerRules(3, 1L, 0D, Double.NaN));
    }

    @Test
    void dropChanceRuleIsAppliedOncePerPieceWithDocumentedBounds() {
        assertFalse(DropChanceRule.drops(0.0F, 0.0F));
        assertFalse(DropChanceRule.drops(-1.0F, 0.0F));
        assertFalse(DropChanceRule.drops(Float.NaN, 0.0F));
        assertTrue(DropChanceRule.drops(1.0F, 0.9999F));
        assertTrue(DropChanceRule.drops(2.0F, 0.9999F));
        assertTrue(DropChanceRule.drops(0.085F, 0.0849F));
        assertFalse(DropChanceRule.drops(0.085F, 0.085F));
        assertTrue(DropChanceRule.drops(Float.POSITIVE_INFINITY, 0.5F));
    }

    @Test
    void dropRuleAlwaysDropsEverythingAndSlotChanceRollsUnlessGuaranteed() {
        assertTrue(DisarmDropRule.ALWAYS.drops(0.0F, false, 0.99F));
        assertTrue(DisarmDropRule.ALWAYS.drops(Float.NaN, false, 0.0F));
        assertFalse(DisarmDropRule.SLOT_CHANCE.drops(0.0F, false, 0.0F));
        assertFalse(DisarmDropRule.SLOT_CHANCE.drops(0.085F, false, 0.5F));
        assertTrue(DisarmDropRule.SLOT_CHANCE.drops(0.085F, false, 0.01F));
        assertTrue(DisarmDropRule.SLOT_CHANCE.drops(0.0F, true, 0.99F));
        assertEquals(java.util.Optional.of(DisarmDropRule.SLOT_CHANCE), DisarmDropRule.fromConfig(" Slot_Chance "));
        assertEquals(java.util.Optional.of(DisarmDropRule.ALWAYS), DisarmDropRule.fromConfig("always"));
        assertTrue(DisarmDropRule.fromConfig("sometimes").isEmpty());
        assertTrue(DisarmDropRule.fromConfig(null).isEmpty());
    }

    @Test
    void targetPolicyExcludesPlayersNonMobsBossesAndOptionallyNpcs() {
        DisarmTargetPolicy defaults = new DisarmTargetPolicy(DisarmTargetPolicy.DEFAULT_EXCLUDED_TYPES, true);
        assertEquals(DisarmTargetPolicy.Verdict.ALLOWED, defaults.evaluate(facts("minecraft:zombie", false, true, false)));
        assertEquals(DisarmTargetPolicy.Verdict.PLAYER, defaults.evaluate(facts("minecraft:player", true, false, false)));
        assertEquals(DisarmTargetPolicy.Verdict.NOT_A_MOB, defaults.evaluate(facts("minecraft:armor_stand", false, false, false)));
        for (String boss : List.of("minecraft:ender_dragon", "minecraft:wither", "minecraft:elder_guardian", "minecraft:warden")) {
            assertEquals(DisarmTargetPolicy.Verdict.EXCLUDED_TYPE, defaults.evaluate(facts(boss, false, true, false)), boss);
        }
        assertEquals(DisarmTargetPolicy.Verdict.NPC, defaults.evaluate(facts("minecraft:zombie", false, true, true)));
        DisarmTargetPolicy custom = new DisarmTargetPolicy(List.of("ZOMBIE", " skeleton ", "", "mymod:golem"), false);
        assertEquals(DisarmTargetPolicy.Verdict.EXCLUDED_TYPE, custom.evaluate(facts("minecraft:zombie", false, true, false)));
        assertEquals(DisarmTargetPolicy.Verdict.EXCLUDED_TYPE, custom.evaluate(facts("minecraft:skeleton", false, true, false)));
        assertEquals(DisarmTargetPolicy.Verdict.EXCLUDED_TYPE, custom.evaluate(facts("mymod:golem", false, true, false)));
        assertEquals(DisarmTargetPolicy.Verdict.ALLOWED, custom.evaluate(facts("minecraft:wither", false, true, true)));
        assertEquals(3, custom.excludedTypes().size());
    }

    @Test
    void legacyImportPreservesChargesDepletedTimestampsUsesAndBrokenState() {
        assertEquals(new DisarmerCharges(3, 0L, false, 0L), LegacyDisarmerImport.importCharges(null, null, false, null, 3));
        assertEquals(new DisarmerCharges(3, 0L, false, 12L), LegacyDisarmerImport.importCharges(3, 0L, false, 12L, 3));
        assertEquals(new DisarmerCharges(2, 0L, false, 1L), LegacyDisarmerImport.importCharges(2, 999L, false, 1L, 3));
        assertEquals(new DisarmerCharges(0, 1_762_000_000_000L, false, 9L),
                LegacyDisarmerImport.importCharges(0, 1_762_000_000_000L, false, 9L, 3));
        assertEquals(new DisarmerCharges(0, 0L, true, 4L), LegacyDisarmerImport.importCharges(2, 50L, true, 4L, 3));
        assertEquals(new DisarmerCharges(2, 0L, false, 0L), LegacyDisarmerImport.importCharges(3, null, false, -5L, 2));
        assertTrue(LegacyDisarmerImport.isLegacyVariant("mob_disarmer_1-3"));
        assertTrue(LegacyDisarmerImport.isLegacyVariant("mob_disarmer"));
        assertFalse(LegacyDisarmerImport.isLegacyVariant("mob_disarmer_4-3"));
        assertFalse(LegacyDisarmerImport.isLegacyVariant(null));
    }

    @Test
    void clickGuardAdmitsOneCommitPerWindowPerPlayer() {
        AtomicLong nanos = new AtomicLong(0L);
        ClickGuard guard = new ClickGuard(200L, nanos::get);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(guard.tryAcquire(a));
        assertFalse(guard.tryAcquire(a));
        assertTrue(guard.tryAcquire(b));
        nanos.set(199_999_999L);
        assertFalse(guard.tryAcquire(a));
        nanos.set(200_000_000L);
        assertTrue(guard.tryAcquire(a));
        guard.forget(a);
        assertTrue(guard.tryAcquire(a));
        ClickGuard disabled = new ClickGuard(0L, nanos::get);
        assertTrue(disabled.tryAcquire(a));
        assertTrue(disabled.tryAcquire(a));
    }

    private static DisarmTargetPolicy.TargetFacts facts(String type, boolean player, boolean mob, boolean npc) {
        return new DisarmTargetPolicy.TargetFacts(type, player, mob, npc);
    }
}
