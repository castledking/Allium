package codes.castled.allium.tool.disarmer;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable charge state persisted on a Mob Disarmer as {@code allium:state_*} values.
 *
 * <p>An absent or unreadable charge value means a full tool, so provider-issued stacks and stacks
 * created under a different {@code maxCharges} start full. A charge count above the configured
 * maximum is clamped to it.
 *
 * @param charges    remaining charges, {@code 0..maxCharges}
 * @param rechargeAt epoch milliseconds when a spent tool refills; {@code 0} when not recharging
 * @param broken     a broken tool never refills or disarms
 * @param uses       successful disarms performed with this tool, saturating
 */
public record DisarmerCharges(int charges, long rechargeAt, boolean broken, long uses) {

    public static final String STATE_CHARGES = "charges";
    public static final String STATE_RECHARGE_AT = "recharge_at";
    public static final String STATE_BROKEN = "broken";
    public static final String STATE_USES = "uses";

    public DisarmerCharges {
        if (charges < 0) {
            throw new IllegalArgumentException("charges must be >= 0; got " + charges);
        }
        if (rechargeAt < 0L) {
            throw new IllegalArgumentException("rechargeAt must be >= 0; got " + rechargeAt);
        }
        if (uses < 0L) {
            throw new IllegalArgumentException("uses must be >= 0; got " + uses);
        }
    }

    public static DisarmerCharges full(int maxCharges) {
        return new DisarmerCharges(maxCharges, 0L, false, 0L);
    }

    public static DisarmerCharges fromState(Map<String, String> state, int maxCharges) {
        Objects.requireNonNull(state, "state");
        Long charges = parseLong(state.get(STATE_CHARGES));
        Long rechargeAt = parseLong(state.get(STATE_RECHARGE_AT));
        Long uses = parseLong(state.get(STATE_USES));
        int clampedCharges = charges == null ? maxCharges : (int) Math.max(0L, Math.min(charges, maxCharges));
        return new DisarmerCharges(
                clampedCharges,
                rechargeAt == null ? 0L : Math.max(0L, rechargeAt),
                "true".equalsIgnoreCase(state.get(STATE_BROKEN)),
                uses == null ? 0L : Math.max(0L, uses)
        );
    }

    /**
     * Refills a spent, unbroken tool whose recharge time has passed. A spent tool without a
     * recharge time refills immediately. All other states are returned unchanged.
     */
    public DisarmerCharges settle(long nowMillis, int maxCharges) {
        if (!broken && charges <= 0 && nowMillis >= rechargeAt) {
            return new DisarmerCharges(maxCharges, 0L, false, uses);
        }
        return this;
    }

    public Availability availability(long nowMillis) {
        if (broken) {
            return new Availability(Availability.Kind.BROKEN, 0L);
        }
        if (charges <= 0) {
            return new Availability(Availability.Kind.RECHARGING, Math.max(0L, rechargeAt - nowMillis));
        }
        return new Availability(Availability.Kind.READY, 0L);
    }

    /**
     * Spends exactly one charge for a successful disarm.
     *
     * @param breakRoll uniform random value in {@code [0, 1)}
     * @throws IllegalStateException when the tool is broken or has no charge
     */
    public Consumption consume(long nowMillis, DisarmerRules rules, double breakRoll) {
        Objects.requireNonNull(rules, "rules");
        if (broken || charges <= 0) {
            throw new IllegalStateException("Cannot consume a charge from " + this);
        }
        long nextUses = uses == Long.MAX_VALUE ? uses : uses + 1L;
        if (breakRoll < rules.breakChance(charges)) {
            return new Consumption(new DisarmerCharges(0, 0L, true, nextUses), true);
        }
        int remaining = charges - 1;
        long nextRechargeAt = remaining == 0 ? saturatingAdd(nowMillis, rules.rechargeMillis()) : 0L;
        return new Consumption(new DisarmerCharges(remaining, nextRechargeAt, false, nextUses), false);
    }

    /** A bypass user's disarm records the use without spending a charge or rolling a break. */
    public DisarmerCharges recordBypassUse() {
        return new DisarmerCharges(charges, rechargeAt, broken, uses == Long.MAX_VALUE ? uses : uses + 1L);
    }

    /** All four values, so every write produces the same canonical signed state. */
    public Map<String, String> toState() {
        Map<String, String> state = new LinkedHashMap<>();
        state.put(STATE_CHARGES, Integer.toString(charges));
        state.put(STATE_RECHARGE_AT, Long.toString(rechargeAt));
        state.put(STATE_BROKEN, Boolean.toString(broken));
        state.put(STATE_USES, Long.toString(uses));
        return state;
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    @Nullable
    private static Long parseLong(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Availability(Kind kind, long remainingMillis) {
        public enum Kind { READY, RECHARGING, BROKEN }
    }

    public record Consumption(DisarmerCharges next, boolean broke) {
    }
}
