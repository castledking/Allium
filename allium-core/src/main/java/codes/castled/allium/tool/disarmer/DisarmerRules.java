package codes.castled.allium.tool.disarmer;

/**
 * Validated charge, recharge and break-chance configuration for the Mob Disarmer.
 *
 * <p>Recharge model: a disarmer holds up to {@code maxCharges}. Each successful disarm spends one.
 * Spending the last charge starts a single timer of {@code rechargeMillis}; when it elapses the
 * tool refills completely. There are no partial refills.
 *
 * <p>Break model: every successful, charge-spending disarm rolls once. The roll uses
 * {@code finalChargeBreakChance} when the spent charge was the last one and
 * {@code perChargeBreakChance} otherwise. Bypass users never roll.
 */
public record DisarmerRules(
        int maxCharges,
        long rechargeMillis,
        double perChargeBreakChance,
        double finalChargeBreakChance
) {
    /** Never breaks. */
    public static final DisarmerRules NO_BREAK = new DisarmerRules(3, 120_000L, 0.0D, 0.0D);
    /** Pre-recode Allium: 3% per charge, 5% on the final charge, 3 charges, 2 minute recharge. */
    public static final DisarmerRules LEGACY = new DisarmerRules(3, 120_000L, 0.03D, 0.05D);

    public DisarmerRules {
        if (maxCharges < 1) {
            throw new IllegalArgumentException("maxCharges must be >= 1; got " + maxCharges);
        }
        if (rechargeMillis < 0L) {
            throw new IllegalArgumentException("rechargeMillis must be >= 0; got " + rechargeMillis);
        }
        requireProbability(perChargeBreakChance, "perChargeBreakChance");
        requireProbability(finalChargeBreakChance, "finalChargeBreakChance");
    }

    /**
     * @param chargesBeforeUse the charge count before the charge being spent
     */
    public double breakChance(int chargesBeforeUse) {
        return chargesBeforeUse <= 1 ? finalChargeBreakChance : perChargeBreakChance;
    }

    private static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(name + " must be between 0 and 1; got " + value);
        }
    }
}
