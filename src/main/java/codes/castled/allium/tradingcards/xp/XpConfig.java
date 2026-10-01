package codes.castled.allium.tradingcards.xp;

import codes.castled.allium.tradingcards.config.ValidationIssue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Per-source xp settings from {@code tradingcards/config.yml}.
 *
 * <p>Each source is declared independently and can be switched off on its own.
 * A source whose plugin is absent is skipped and logged once — never an error,
 * because a server without ExcellentQuests should not have to delete its config
 * to make the rest of the file quiet.
 *
 * @param sources  settings per source id
 * @param curve    xp required to go from level N to N+1
 */
public record XpConfig(
    Map<String, Source> sources,
    Map<Integer, Double> curveBreakpoints
) {

    public static final String SECTION = "xp";

    public XpConfig {
        sources = Map.copyOf(sources);
        curveBreakpoints = Map.copyOf(curveBreakpoints);
    }

    /**
     * One xp source.
     *
     * @param xp             flat xp awarded, or 0 when a multiplier is used
     * @param multiplier     scales the event's own xp, or 0 when a flat amount
     *                       is used. A source uses one or the other, never
     *                       both: adding a flat amount to a plugin's own xp
     *                       is not a thing anybody can reason about.
     * @param cooldownMillis minimum gap between awards from this source
     */
    public record Source(
        boolean enabled,
        double xp,
        double multiplier,
        long cooldownMillis,
        boolean perMobCooldown,
        long perMobCooldownMillis,
        int maxSamples,
        double percentile,
        int minimumSamples
    ) {
        /**
         * The cooldown a plain claim is gated by: the per-source one, or the
         * per-mob one when this source keys by mob type. A per-mob source with
         * no explicit per-source cooldown is NOT gated by a plain cooldown,
         * because that would silently re-introduce the farm the per-mob keying
         * exists to prevent.
         */
        public static Source flat(double xp) {
            return new Source(true, xp, 0.0, 0L, false, 0L, 0, 0.0, 0);
        }

        public static Source disabled() {
            return new Source(false, 0.0, 0.0, 0L, false, 0L, 0, 0.0, 0);
        }

        public boolean usesMultiplier() {
            return multiplier > 0.0;
        }
    }

    public static XpConfig defaults() {
        Map<String, Source> sources = new LinkedHashMap<>();
        sources.put("kill-mob", Source.flat(4));
        sources.put("breed-mob", new Source(true, 40, 0.0, 0L, true, 604_800_000L, 0, 0.0, 0));
        sources.put("farm-crop", Source.flat(2));
        sources.put("auraskills-ability", Source.flat(3));
        sources.put("quest-complete", Source.flat(25));
        sources.put("fish-large", Source.flat(12));
        // EcoJobs pays a multiple of the job's own experience rather than a
        // flat amount: the event fires per counter, not per action, so counting
        // events would pay more for a job that merely has more triggers.
        sources.put("ecojobs-work", new Source(true, 0.0, 1.5, 0L, false, 0L, 0, 0.0, 0));
        sources.put("advancement", Source.flat(20));
        return new XpConfig(sources, defaultCurve());
    }

    public Source source(String id) {
        return sources.getOrDefault(id, Source.disabled());
    }

    public boolean isEnabled(String id) {
        return source(id).enabled();
    }

    // ==================== curve ====================

    /** Flat early, steep late: the first levels arrive fast, the last are a project. */
    private static Map<Integer, Double> defaultCurve() {
        Map<Integer, Double> curve = new LinkedHashMap<>();
        curve.put(10, 25.0);
        curve.put(25, 120.0);
        curve.put(50, 600.0);
        curve.put(75, 2400.0);
        curve.put(100, 9000.0);
        return curve;
    }

    /**
     * Xp to go from {@code level} to {@code level + 1}.
     *
     * <p>Read from the nearest breakpoint <b>at or above</b> the level, so a
     * level with no explicit entry inherits the next one's cost. That is what
     * makes the table readable: five rows describe a hundred levels.
     *
     * <p>Direction matters. Breakpoints are costs that step UP as the card
     * climbs, so {@code 10: 25} means "levels 0-9 each cost 25" and
     * {@code 25: 120} means "levels 10-24 each cost 120". Reading downward
     * instead would make every level cost the cheapest row, and the curve
     * would be flat.
     */
    public double xpForLevel(int level) {
        if (level < 0) level = 0;
        double cheapestAbove = Double.MAX_VALUE;
        double highest = Double.MAX_VALUE;
        for (Map.Entry<Integer, Double> entry : curveBreakpoints.entrySet()) {
            highest = Math.max(highest, entry.getValue());
            if (entry.getKey() >= level) {
                cheapestAbove = Math.min(cheapestAbove, entry.getValue());
            }
        }
        if (cheapestAbove != Double.MAX_VALUE) {
            return cheapestAbove;
        }
        // Above the last breakpoint: the last one's cost, so a level past the
        // table never costs nothing and never stalls.
        return highest == Double.MAX_VALUE ? 25.0 : highest;
    }

    /** The first level whose cost is at least {@code xp}, for a progress bar. */
    public int levelForXp(double xp) {
        int level = 0;
        double spent = 0.0;
        for (int candidate = 0; candidate < 1000; candidate++) {
            double needed = xpForLevel(candidate);
            if (spent + needed > xp) {
                return candidate;
            }
            spent += needed;
            level = candidate + 1;
        }
        return level;
    }

    // ==================== loading ====================

    public static LoadResult load(ConfigurationSection yaml) {
        Map<String, Source> sources = new LinkedHashMap<>();
        List<ValidationIssue> issues = new ArrayList<>();
        XpConfig defaults = defaults();

        Map<Integer, Double> curve = defaultCurve();
        ConfigurationSection curveSection = yaml == null ? null
            : yaml.getConfigurationSection(SECTION + ".curve");
        if (curveSection != null) {
            Map<Integer, Double> parsed = new LinkedHashMap<>();
            for (String key : curveSection.getKeys(false)) {
                try {
                    int level = Integer.parseInt(key.trim());
                    double value = curveSection.getDouble(key, 0.0);
                    if (value <= 0.0) {
                        issues.add(ValidationIssue.warning(FILE,
                            SECTION + ".curve." + key,
                            "Xp must be positive, got " + value + "; skipping"));
                        continue;
                    }
                    parsed.put(level, value);
                } catch (NumberFormatException e) {
                    issues.add(ValidationIssue.warning(FILE,
                        SECTION + ".curve." + key,
                        "Level '" + key + "' is not a number; skipping"));
                }
            }
            if (!parsed.isEmpty()) {
                curve = parsed;
            }
        }

        ConfigurationSection sourceSection = yaml == null ? null
            : yaml.getConfigurationSection(SECTION + ".sources");
        if (sourceSection != null) {
            for (String id : sourceSection.getKeys(false)) {
                String path = SECTION + ".sources." + id;
                ConfigurationSection entry = sourceSection.getConfigurationSection(id);
                if (entry == null) {
                    issues.add(ValidationIssue.error(FILE, path, "Source is not a section"));
                    continue;
                }
                String normalised = id.toLowerCase(Locale.ROOT);
                if (!defaults.sources().containsKey(normalised)) {
                    // A typo here would otherwise be a source that silently
                    // never fires, which reads as "the feature is broken".
                    issues.add(ValidationIssue.warning(FILE, path,
                        "Unknown xp source '" + id + "'. Known: "
                            + String.join(", ", defaults.sources().keySet())));
                    continue;
                }
                double xp = entry.getDouble("xp", 0.0);
                double multiplier = entry.getDouble("multiplier", 0.0);
                if (xp < 0.0) multiplier = 0.0;
                if (multiplier < 0.0) multiplier = 0.0;
                boolean enabled = entry.getBoolean("enabled", true);
                if (enabled && xp <= 0.0 && multiplier <= 0.0) {
                    issues.add(ValidationIssue.warning(FILE, path,
                        "Enabled but awards nothing; it will never fire. "
                            + "Set an xp amount or a multiplier, or disable it."));
                    enabled = false;
                }
                // The three trailing fields are fishing-only and ignored by
                // every other source, so one record type covers the block
                // without a fishing-specific class that nothing else uses.
                int maxSamples = entry.getInt("max-samples", 40);
                if (maxSamples < 1) maxSamples = 1;
                double percentile = entry.getDouble("minimum-percentile", 0.75);
                if (percentile <= 0.0 || percentile > 1.0) {
                    issues.add(ValidationIssue.warning(FILE, path + ".minimum-percentile",
                        "Must be in (0,1], got " + percentile + "; using 0.75"));
                    percentile = 0.75;
                }
                int minimumSamples = entry.getInt("minimum-samples", 8);
                if (minimumSamples < 1) minimumSamples = 1;
                sources.put(normalised, new Source(
                    enabled, xp, multiplier,
                    parseDuration(entry.getString("cooldown", "0")),
                    entry.getBoolean("per-mob-cooldown", false),
                    parseDuration(entry.getString("per-mob-cooldown-duration", "0")),
                    maxSamples, percentile, minimumSamples));
            }
        }
        // Anything the file did not mention keeps its default, so a partial
        // file does not silently disable sources.
        defaults.sources().forEach((id, value) -> sources.putIfAbsent(id, value));

        return new LoadResult(new XpConfig(sources, curve), issues);
    }

    public record LoadResult(XpConfig config, List<ValidationIssue> issues) {}

    private static final String FILE = "config.yml";

    /**
     * Parses a duration like {@code 7d}, {@code 30m} or {@code 0}.
     *
     * <p>Written out rather than pulled in, because a cooldown is a number an
     * operator edits by hand and a typo must cost them that line rather than
     * throw during load.
     */
    public static long parseDuration(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.equals("never")) return 0L;
            long multiplier = 1L;
            char unit = text.charAt(text.length() - 1);
            String number = text.substring(0, text.length() - 1).trim();
            switch (unit) {
                case 'd' -> multiplier = 86_400_000L;
                case 'h' -> multiplier = 3_600_000L;
                case 'm' -> multiplier = 60_000L;
                case 's' -> multiplier = 1_000L;
                default -> {
                    return (long) (Double.parseDouble(text) * 1000.0);
                }
            }
            return (long) (Double.parseDouble(number) * multiplier);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Formats a duration back out, for a menu line. */
    public static String formatDuration(long millis) {
        if (millis <= 0) return "never";
        long days = millis / 86_400_000L;
        long hours = (millis % 86_400_000L) / 3_600_000L;
        long minutes = (millis % 3_600_000L) / 60_000L;
        if (days > 0) return days + "d";
        if (hours > 0) return hours + "h";
        if (minutes > 0) return minutes + "m";
        return (millis / 1000L) + "s";
    }

    /** Per-source ids that use a multiplier rather than a flat amount. */
    public List<String> multiplierSources() {
        List<String> out = new ArrayList<>();
        sources.forEach((id, source) -> {
            if (source.usesMultiplier()) out.add(id);
        });
        return out;
    }

    /**
     * Sources whose anti-farm state must survive a restart.
     *
     * <p>Anything with a cooldown. A cooldown held only in memory is not a
     * cooldown — a player who reconnects, or a server that restarts, would find
     * every gate wide open, and the gate is the entire point of having one.
     */
    public List<String> sourcesNeedingPersistence() {
        List<String> out = new ArrayList<>();
        sources.forEach((id, source) -> {
            if (source.perMobCooldown() || source.cooldownMillis() > 0L) {
                out.add(id);
            }
        });
        return out;
    }
}
