package codes.castled.allium.tradingcards.config;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import codes.castled.allium.tradingcards.card.QualityBand;
import codes.castled.allium.tradingcards.card.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Global trading card settings from {@code tradingcards/config.yml}.
 *
 * <p>Card definitions live in {@code cards.yml}; this file holds the rules
 * every card obeys — the quality ladder, the level curve, and the drop
 * mechanics.
 *
 * <p>Every value is clamped or defaulted, and every unrecognised enum falls
 * back. A typo costs that one setting rather than the module.
 */
public record TradingCardsConfig(
    boolean enabled,
    Levelling levelling,
    List<QualityBand> quality,
    Trade trade,
    Reroll reroll,
    BonusSlotRoll bonusSlotRoll,
    Merge merge,
    Morph morph,
    Crafting crafting
) {

    public static final String FILE = "config.yml";

    public TradingCardsConfig {
        quality = List.copyOf(quality);
    }

    /** A config that does nothing, used before the first successful load. */
    public static TradingCardsConfig disabled() {
        return new TradingCardsConfig(false, Levelling.defaults(), List.of(),
            Trade.defaults(), Reroll.defaults(), BonusSlotRoll.defaults(),
            Merge.defaults(), Morph.defaults(), Crafting.defaults());
    }

    // ==================== nested blocks ====================

    /**
     * Card level curve and the level-up announcement.
     *
     * @param startLevel  level a freshly dropped card sits at; a merged card
     *                    lands here too, so merging is never a head start
     * @param maximumLevel the ceiling, and the level at which a card can merge
     * @param boostPerLevel added to one signature boost per level
     * @param boostsPerLevel how many of the card's signatures grow per level
     */
    public record Levelling(
        int startLevel,
        int maximumLevel,
        double boostPerLevel,
        int boostsPerLevel,
        String loreSeparator,
        int loreBonusSlots,
        Announce announce
    ) {
        public static Levelling defaults() {
            return new Levelling(0, 100, 1.0, 1,
                "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
                5, Announce.defaults());
        }

        /** The rule between lore sections; blank omits every separator. */
        public String loreSeparator() {
            return loreSeparator == null ? "" : loreSeparator;
        }
    }

    public record Announce(boolean enabled, boolean broadcast, String message) {
        public static Announce defaults() {
            return new Announce(true, false,
                "<dark_gray>(<gold><bold>TRADING CARD</bold></dark_gray>) <yellow>Your "
                    + "<card> is now level <green><level></green>! <dark_green><previous>"
                    + "</dark_green> <white>→</white> <green><level></green>");
        }
    }

    /**
     * Trading a card in for its mob's head.
     *
     * @param showWorth print each band's head payout on the card menu's
     *                  quality ladder. Off by default: the payout is only
     *                  committed to once the trade window confirms, so a
     *                  figure on the card is a quote the next click can
     *                  contradict.
     */
    public record Trade(
        boolean enabled,
        HeadSource headSource,
        boolean showWorth,
        boolean requireMintOrBetter,
        boolean consumeCard
    ) {
        public static Trade defaults() {
            return new Trade(true, HeadSource.SPAWNER_HEADS, false, false, true);
        }
    }

    /**
     * Where a traded card's head comes from.
     *
     * <p>Spawner heads rather than plain vanilla skulls by default, because
     * Allium's mob heads already have a purpose — they craft spawner cores and
     * plushies — so a card feeds that progression instead of introducing a
     * second currency nobody else accepts.
     */
    public enum HeadSource {
        /** Allium's own mob head from {@code spawner_heads.yml}. */
        SPAWNER_HEADS,
        /** Only the card's explicit {@code head:} reference. */
        CARD_ITEM;

        static HeadSource parse(String raw) {
            if (raw == null) return SPAWNER_HEADS;
            for (HeadSource source : values()) {
                if (source.name().equalsIgnoreCase(raw.trim())) return source;
            }
            return SPAWNER_HEADS;
        }
    }

    /**
     * Bonus slot pricing.
     *
     * <p>A slot is rolled on its own, so this is a flat fee per slot rather than
     * a ladder: the escalating reroll price exists to stop one card being
     * rerolled forever, and a player choosing to spend on a specific slot has
     * already decided how many they want.
     *
     * <p>The tier multiplier still applies, so filling every slot on a Fabled
     * card costs more than on a SIMPLE one — which is the point of the ladder.
     */
    public record BonusSlotRoll(
        boolean enabled,
        double cost,
        double escalation,
        long roundTo,
        java.util.Map<Tier, Double> tierMultipliers
    ) {
        public BonusSlotRoll {
            tierMultipliers = java.util.Map.copyOf(tierMultipliers);
        }

        public static BonusSlotRoll defaults() {
            // The same ladder config.yml ships. Flat multipliers here would make a
            // server whose config predates the block charge a Fabled card the same
            // as a SIMPLE one, so the top of the ladder would buy nothing.
            java.util.Map<Tier, Double> ladder = new java.util.EnumMap<>(Tier.class);
            ladder.put(Tier.SIMPLE, 1.0);
            ladder.put(Tier.ELITE, 1.5);
            ladder.put(Tier.ULTIMATE, 2.0);
            ladder.put(Tier.LEGENDARY, 3.0);
            ladder.put(Tier.FABLED, 5.0);
            return new BonusSlotRoll(true, 500.0, 1.35, 100L, ladder);
        }

        /**
         * The price of the next roll on a slot that has already been rolled
         * {@code rolls} times, counting from 0.
         *
         * <p>Escalating per slot for the same reason a reroll escalates: a flat
         * fee makes "roll until satisfied" free of friction, and the player
         * sees the next price before committing rather than after.
         */
        public double costFor(int rolls, Tier tier) {
            if (rolls < 0) rolls = 0;
            double tierMultiplier = tierMultipliers.getOrDefault(
                tier == null ? Tier.SIMPLE : tier, 1.0);
            double raw = cost * Math.pow(escalation, rolls) * tierMultiplier;
            if (roundTo > 1) {
                raw = Math.round(raw / roundTo) * (double) roundTo;
            }
            return Math.max(roundTo > 1 ? roundTo : 1.0, raw);
        }
    }

    /**
     * Reroll pricing. Progressive rather than flat: a flat fee makes "reroll
     * forever" the optimum, and a linear fee makes the tail affordable to
     * anyone who saved up.
     */
    public record Reroll(
        boolean enabled,
        double baseCost,
        double escalation,
        long roundTo,
        double maximumCost,
        java.util.Map<Tier, Double> tierMultipliers,
        double signatureUnlockChance,
        int maximumSignatures,
        boolean allowBonusOnlyWhenFull
    ) {
        public Reroll {
            tierMultipliers = java.util.Map.copyOf(tierMultipliers);
        }

        public static Reroll defaults() {
            java.util.Map<Tier, Double> flat = new java.util.EnumMap<>(Tier.class);
            for (var t : Tier.values()) {
                flat.put(t, 1.0);
            }
            return new Reroll(true, 2000.0, 1.6, 100L, 500000.0, flat, 0.35, 6, true);
        }

        /**
         * Cost of the {@code attempt}-th reroll on a card of {@code tier},
         * counting from 1. This is the number the menu shows, so it is
         * computed in one place and rounded once.
         */
        public double costFor(int attempt, Tier tier) {
            if (attempt < 1) attempt = 1;
            double tierMultiplier = tierMultipliers.getOrDefault(tier, 1.0);
            double raw = baseCost * Math.pow(escalation, attempt - 1.0) * tierMultiplier;
            if (maximumCost > 0.0 && raw > maximumCost) raw = maximumCost;
            if (roundTo > 1) {
                raw = Math.round(raw / roundTo) * (double) roundTo;
            }
            return Math.max(roundTo > 1 ? roundTo : 1.0, raw);
        }
    }

    /**
     * Merging two cards into one of the next tier.
     *
     * @param targetLevel the level a merged card lands at; 0 matches where a
     *                    dropped card starts, so merging converts spares
     *                    rather than granting a head start
     */
    public record Merge(
        boolean enabled,
        boolean requireSameMob,
        int targetLevel
    ) {
        public static Merge defaults() {
            return new Merge(true, true, 0);
        }
    }

    /**
     * Turning into the mob on a FABLED card.
     *
     * <p>The disguise itself is cosmetic — LibsDisguises changes packets only,
     * so the server still treats the player as a player. Everything that makes a
     * morph <i>behave</i> like a mob is implemented by this module, and the
     * fields below govern that behaviour rather than the costume.
     *
     * @param minimumTier  the lowest tier that may morph
     * @param durationSeconds 0 for a toggle that never auto-expires
     * @param deactivateBelowHealth 0 disables; 10 is half of vanilla 20
     * @param allowFlight  whether fliers may take flight at all — the single
     *                     most abusable thing in the set, so opt-in per mob
     * @param stealthUntilAttacked hold off mob aggro until the player strikes
     * @param mobsTargetDisguised pass through to LibsDisguises; false makes the
     *                            morphed player invisible to mob AI entirely,
     *                            which is the opposite of the point
     */
    public record Morph(
        boolean enabled,
        Tier minimumTier,
        int durationSeconds,
        double deactivateBelowHealth,
        boolean allowFlight,
        boolean stealthUntilAttacked,
        boolean mobsTargetDisguised
    ) {
        public static Morph defaults() {
            return new Morph(true, Tier.FABLED, 0, 0.0, false, true, true);
        }
    }

    /**
     * Crafting cards from heads. Off by default: heads buy cards and cards buy
     * heads, so enabling both without binding is an infinite loop.
     */
    public record Crafting(
        boolean enabled,
        codes.castled.allium.tradingcards.card.Tier resultTier,
        int resultLevel,
        String resultQuality,
        boolean consume,
        boolean bound,
        Accept accept
    ) {
        public static Crafting defaults() {
            return new Crafting(false, codes.castled.allium.tradingcards.card.Tier.SIMPLE, 0,
                "emaculate", true, true, Accept.defaults());
        }

        /** Which head sources count as ingredients. */
        public record Accept(boolean allium, boolean datapack, boolean vanillaSkulls) {
            public static Accept defaults() {
                return new Accept(true, true, false);
            }
        }
    }

    // ==================== loading ====================

    public static TradingCardsConfig from(ConfigurationSection yaml) {
        return load(yaml).config();
    }

    /** Parses the file, collecting problems rather than throwing. */
    public static LoadResult load(ConfigurationSection yaml) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (yaml == null) {
            issues.add(ValidationIssue.error(FILE, "",
                "File is missing or unreadable; trading cards stay disabled"));
            return new LoadResult(disabled(), issues);
        }
        TradingCardsConfig config = new TradingCardsConfig(
            yaml.getBoolean("enabled", true),
            levelling(yaml.getConfigurationSection("levelling"), issues),
            quality(yaml.getConfigurationSection("quality"), issues),
            trade(yaml.getConfigurationSection("trade"), issues),
            reroll(yaml.getConfigurationSection("reroll"), issues),
            bonusSlotRoll(yaml.getConfigurationSection("bonus-slot-roll"), issues),
            merge(yaml.getConfigurationSection("merge"), issues),
            morph(yaml.getConfigurationSection("morph"), issues),
            crafting(yaml.getConfigurationSection("crafting"), issues));
        return new LoadResult(config, issues);
    }

    /** Result of one load pass. */
    public record LoadResult(TradingCardsConfig config, List<ValidationIssue> issues) {
        public List<ValidationIssue> issues() {
            return List.copyOf(issues);
        }

        public boolean hasErrors() {
            return issues.stream().anyMatch(ValidationIssue::isError);
        }
    }

    private static Levelling levelling(ConfigurationSection section, List<ValidationIssue> issues) {
        if (section == null) return Levelling.defaults();
        int start = clamp(section.getInt("start-level", 0), 0, 1000);
        int max = clamp(section.getInt("maximum-level", 100), start + 1, 1000);
        String separator = section.getString("lore-separator",
            "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"
                + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"
                + "\u2500\u2500\u2500\u2500\u2500");
        double perLevel = section.getDouble("boost-per-level", 1.0);
        if (perLevel < 0.0) {
            issues.add(ValidationIssue.warning(FILE, "levelling.boost-per-level",
                "Negative value; levelling would reduce a card's boosts. Using 0."));
            perLevel = 0.0;
        }
        int perLevelCount = clamp(section.getInt("boosts-per-level", 1), 1, 9);
        // Slots shown on a card, independent of how many bonuses a drop rolls:
        // the lore shows the card's full potential so a player can see what is
        // still available, which is not the same as how many it starts with.
        int slots = clamp(section.getInt("lore-bonus-slots", 5), 1, 9);
        return new Levelling(start, max, perLevel, perLevelCount, separator, slots,
            announce(section.getConfigurationSection("announce")));
    }

    private static Announce announce(ConfigurationSection section) {
        if (section == null) return Announce.defaults();
        String message = section.getString("message");
        if (message == null || message.isBlank()) {
            message = Announce.defaults().message();
        }
        return new Announce(section.getBoolean("enabled", true),
            section.getBoolean("broadcast", false), message);
    }

    private static List<QualityBand> quality(ConfigurationSection section,
                                             List<ValidationIssue> issues) {
        List<QualityBand> bands = new ArrayList<>();
        if (section == null) {
            issues.add(ValidationIssue.error(FILE, "quality",
                "No quality section; cards cannot be rolled"));
            return bands;
        }
        ConfigurationSection bandSection = section.getConfigurationSection("bands");
        if (bandSection == null) {
            issues.add(ValidationIssue.error(FILE, "quality.bands",
                "No quality bands configured"));
            return bands;
        }
        for (String id : bandSection.getKeys(false)) {
            String path = "quality.bands." + id;
            ConfigurationSection band = bandSection.getConfigurationSection(id);
            if (band == null) {
                issues.add(ValidationIssue.error(FILE, path,
                    "Quality band is not a section"));
                continue;
            }
            int min = band.getInt("min", -1);
            int max = band.getInt("max", -1);
            int heads = band.getInt("heads", 1);
            String colour = QualityBand.parseColour(band.getString("colour"));
            try {
                bands.add(new QualityBand(id, min, max, heads, colour));
            } catch (IllegalArgumentException e) {
                issues.add(ValidationIssue.error(FILE, path, e.getMessage()));
            }
        }
        for (String problem : QualityBand.validate(bands)) {
            // A gap means a rolled quality has no band, so it would have no
            // colour and no head payout. That is an error, not a warning.
            issues.add(ValidationIssue.error(FILE, "quality.bands", problem));
        }
        return bands;
    }

    private static Trade trade(ConfigurationSection section, List<ValidationIssue> issues) {
        if (section == null) return Trade.defaults();
        String headSourceRaw = section.getString("head-source");
        HeadSource headSource = HeadSource.parse(headSourceRaw);
        if (headSourceRaw != null && headSource == HeadSource.SPAWNER_HEADS
            && !HeadSource.SPAWNER_HEADS.name().equalsIgnoreCase(headSourceRaw.trim())) {
            issues.add(ValidationIssue.warning(FILE, "trade.head-source",
                "Unknown head source '" + headSourceRaw + "'; using SPAWNER_HEADS"));
        }
        return new Trade(section.getBoolean("enabled", true), headSource,
            section.getBoolean("show-worth", false),
            section.getBoolean("require-mint-or-better", false),
            section.getBoolean("consume-card", true));
    }

    private static Reroll reroll(ConfigurationSection section, List<ValidationIssue> issues) {
        if (section == null) return Reroll.defaults();
        double base = section.getDouble("base-cost", 2000.0);
        if (base < 0.0) base = 0.0;
        double escalation = section.getDouble("escalation", 1.6);
        if (escalation < 1.0) {
            // Below 1.0 every reroll gets cheaper, which inverts the whole
            // point: the player would reroll until the price hit zero.
            issues.add(ValidationIssue.warning(FILE, "reroll.escalation",
                "Below 1.0, so rerolls get cheaper each time. Using 1.0 (flat)."));
            escalation = 1.0;
        }
        long roundTo = section.getLong("round-to", 100L);
        if (roundTo < 1) roundTo = 1;

        var multipliers = new java.util.EnumMap<codes.castled.allium.tradingcards.card.Tier, Double>(
            codes.castled.allium.tradingcards.card.Tier.class);
        for (var tier : codes.castled.allium.tradingcards.card.Tier.values()) {
            multipliers.put(tier, 1.0);
        }
        ConfigurationSection tierSection = section.getConfigurationSection("tier-multipliers");
        if (tierSection != null) {
            for (String key : tierSection.getKeys(false)) {
                var tier = codes.castled.allium.tradingcards.card.CardDefinition.parseTier(key);
                if (tier == null) {
                    issues.add(ValidationIssue.warning(FILE, "reroll.tier-multipliers." + key,
                        "Unknown tier '" + key + "'; using 1.0"));
                    continue;
                }
                multipliers.put(tier, Math.max(0.0, tierSection.getDouble(key, 1.0)));
            }
        }
        double unlockChance = section.getDouble("signature-unlock-chance", 0.35);
        if (unlockChance < 0.0 || unlockChance > 1.0) {
            issues.add(ValidationIssue.warning(FILE, "reroll.signature-unlock-chance",
                "Must be in (0,1], got " + unlockChance + "; using 0.35"));
            unlockChance = 0.35;
        }
        int maxSignatures = section.getInt("maximum-signatures", 6);
        if (maxSignatures < 3) {
            // 3 is where every card starts; below that a card would have to
            // lose a signature to equip, which nothing supports.
            issues.add(ValidationIssue.warning(FILE, "reroll.maximum-signatures",
                "Below 3, which is where every card starts. Using 3."));
            maxSignatures = 3;
        }
        return new Reroll(section.getBoolean("enabled", true), base, escalation, roundTo,
            section.getDouble("maximum-cost", 500000.0), multipliers, unlockChance,
            maxSignatures, section.getBoolean("allow-bonus-only-when-full", true));
    }

    private static BonusSlotRoll bonusSlotRoll(ConfigurationSection section,
                                               List<ValidationIssue> issues) {
        // An absent section is a config that predates the block, not an error:
        // the defaults are what that config would have meant anyway.
        if (section == null) {
            return BonusSlotRoll.defaults();
        }
        var multipliers = new java.util.EnumMap<Tier, Double>(Tier.class);
        for (var t : Tier.values()) {
            multipliers.put(t, section.getDouble("tier-multipliers." + t.name(), 1.0));
        }
        double cost = section.getDouble("cost", 500.0);
        if (cost < 0) {
            issues.add(ValidationIssue.warning(FILE, "bonus-slot-roll.cost",
                "Negative, which would pay the player to roll. Using 0."));
            cost = 0.0;
        }
        double escalation = section.getDouble("escalation", 1.35);
        if (escalation < 1.0) {
            issues.add(ValidationIssue.warning(FILE, "bonus-slot-roll.escalation",
                "Below 1, so each roll would cost less than the last. Using 1."));
            escalation = 1.0;
        }
        return new BonusSlotRoll(section.getBoolean("enabled", true), cost, escalation,
            section.getLong("round-to", 100L), multipliers);
    }

    private static Merge merge(ConfigurationSection section, List<ValidationIssue> issues) {
        if (section == null) return Merge.defaults();
        int target = section.getInt("target-level", 0);
        if (target < 0) {
            issues.add(ValidationIssue.warning(FILE, "merge.target-level",
                "Negative; a merged card cannot start below 0. Using 0."));
            target = 0;
        }
        return new Merge(section.getBoolean("enabled", true),
            section.getBoolean("require-same-mob", true), target);
    }

    private static Morph morph(ConfigurationSection section, List<ValidationIssue> issues) {
        if (section == null) return Morph.defaults();
        Tier tier = Tier.SIMPLE;
        if (section.isString("minimum-tier")) {
            Tier parsed = codes.castled.allium.tradingcards.card.CardDefinition.parseTier(
                section.getString("minimum-tier"));
            if (parsed == null) {
                issues.add(ValidationIssue.warning(FILE, "morph.minimum-tier",
                    "Unknown tier '" + section.getString("minimum-tier")
                        + "'; using FABLED"));
                tier = Tier.FABLED;
            } else {
                tier = parsed;
            }
        } else {
            tier = Tier.FABLED;
        }
        int duration = section.getInt("duration", 0);
        if (duration < 0) duration = 0;
        double health = section.getDouble("deactivate-below-health", 0.0);
        if (health < 0.0) {
            issues.add(ValidationIssue.warning(FILE, "morph.deactivate-below-health",
                "Negative; using 0, which disables the check"));
            health = 0.0;
        }
        return new Morph(section.getBoolean("enabled", true), tier, duration, health,
            section.getBoolean("allow-flight", false),
            section.getBoolean("stealth-until-attacked", true),
            section.getBoolean("mobs-target-disguised", true));
    }

    private static Crafting crafting(ConfigurationSection section, List<ValidationIssue> issues) {
        if (section == null) return Crafting.defaults();
        var tier = codes.castled.allium.tradingcards.card.Tier.SIMPLE;
        if (section.isString("result-tier")) {
            var parsed = codes.castled.allium.tradingcards.card.CardDefinition.parseTier(
                section.getString("result-tier"));
            if (parsed == null) {
                issues.add(ValidationIssue.warning(FILE, "crafting.result-tier",
                    "Unknown tier '" + section.getString("result-tier") + "'; using SIMPLE"));
            } else {
                tier = parsed;
            }
        }
        boolean bound = section.getBoolean("bound", true);
        if (section.getBoolean("enabled", false) && !bound) {
            // Heads buy cards and cards buy heads. Without binding, an operator
            // who turns crafting on has built a money printer, so say so loudly
            // rather than letting them discover it in economy.
            issues.add(ValidationIssue.warning(FILE, "crafting.bound",
                "Crafting is enabled with bound: false, so crafted cards can be "
                    + "traded back for the same 8 heads. Set bound: true unless "
                    + "the head trade is disabled for crafted cards."));
        }
        ConfigurationSection acceptSection = section.getConfigurationSection("accept");
        Crafting.Accept accept = acceptSection == null
            ? Crafting.Accept.defaults()
            : new Crafting.Accept(
                acceptSection.getBoolean("allium", true),
                acceptSection.getBoolean("datapack", true),
                acceptSection.getBoolean("vanilla-skulls", false));
        String quality = section.getString("result-quality", "emaculate")
            .trim().toLowerCase(Locale.ROOT);
        return new Crafting(section.getBoolean("enabled", false), tier,
            clamp(section.getInt("result-level", 0), 0, 1000), quality,
            section.getBoolean("consume", true), bound, accept);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
