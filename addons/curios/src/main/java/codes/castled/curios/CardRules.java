package codes.castled.curios;

import org.bukkit.configuration.ConfigurationSection;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SF-compatible trading card rules: tiers, quality bands, piecewise XP curve, deterministic
 * starting signature boosts, bonus boost pools/slots, merge rules, and small-caps text formatting.
 */
final class CardRules {

    enum Tier {
        SIMPLE(1, "gray", "§7", "\ue150", 0xe100),
        ELITE(2, "aqua", "§b", "\ue151", 0xe110),
        ULTIMATE(3, "light_purple", "§d", "\ue152", 0xe120),
        LEGENDARY(4, "gold", "§6", "\ue153", 0xe130),
        FABLED(5, "red", "§c", "\ue154", 0xe140);

        final int bonusPoints;
        final String colourName;
        final String legacyColour;
        final String badgeGlyph;
        final int frameBase;

        Tier(int bonusPoints, String colourName, String legacyColour, String badgeGlyph, int frameBase) {
            this.bonusPoints = bonusPoints;
            this.colourName = colourName;
            this.legacyColour = legacyColour;
            this.badgeGlyph = badgeGlyph;
            this.frameBase = frameBase;
        }

        String headerGlyph() { return String.valueOf((char) frameBase); }
        String bodyGlyph() { return String.valueOf((char) (frameBase + 1)); }
        String bottomGlyph() { return String.valueOf((char) (frameBase + 2)); }

        Tier next() {
            int idx = ordinal() + 1;
            return idx < values().length ? values()[idx] : null;
        }

        static Tier parse(String raw) {
            if (raw == null || raw.isBlank()) return SIMPLE;
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return SIMPLE;
            }
        }
    }

    record QualityBand(String id, int min, int max, int heads, String colour, int boostMin, int boostMax) {
        boolean contains(int quality) { return quality >= min && quality <= max; }
        String label() { return titleCase(id); }
    }

    record StartingValue(String boostId, double base, int points) {
        double total() { return base + points; }
        String pointsSuffixMini() {
            return points <= 0 ? "" : " <dark_gray>(</dark_gray><white>+" + points + "</white><dark_gray>)</dark_gray>";
        }
        String pointsSuffixLegacy() {
            return points <= 0 ? "" : " §8(§f+" + points + "§8)";
        }
    }

    /**
     * One bonus slot. {@code rolls} and {@code spent} survive clearing, as in legacy: the player paid
     * for those rolls, and keeping the count keeps the next roll's price where the ladder put it.
     */
    record BonusSlotData(String boostId, int strength, boolean locked, int rolls, double spent) {
        static final BonusSlotData EMPTY = new BonusSlotData("", 0, false, 0, 0);
        BonusSlotData {
            spent = Double.isFinite(spent) ? Math.max(0, spent) : 0;
        }
        BonusSlotData(String boostId, int strength, boolean locked, int rolls) {
            this(boostId, strength, locked, rolls, 0);
        }
        boolean isEmpty() { return boostId == null || boostId.isBlank(); }
        BonusSlotData withLocked(boolean value) { return new BonusSlotData(boostId, strength, value, rolls, spent); }
        BonusSlotData cleared() { return new BonusSlotData("", 0, false, rolls, spent); }
        BonusSlotData paid(double cost) { return new BonusSlotData(boostId, strength, locked, rolls, spent + Math.max(0, cost)); }
    }

    /**
     * Legacy's bonus-slot-roll price ladder: the next roll of a slot costs {@code cost} times
     * {@code escalation} for every earlier roll of that slot, times the card tier's multiplier,
     * rounded to {@code roundTo}. Each roll costs more than the last, so rolling until satisfied is
     * never free, and the menu shows the next price before the player commits.
     */
    record RollPricing(boolean enabled, double cost, double escalation, long roundTo, Map<Tier, Double> tierMultipliers) {
        static final RollPricing LEGACY = new RollPricing(true, 500.0, 1.35, 100L, Map.of(
                Tier.SIMPLE, 1.0, Tier.ELITE, 1.5, Tier.ULTIMATE, 2.0, Tier.LEGENDARY, 3.0, Tier.FABLED, 5.0));

        RollPricing {
            tierMultipliers = Map.copyOf(tierMultipliers);
        }

        /** The price of the next roll on a slot already rolled {@code rolls} times. */
        double costFor(int rolls, Tier tier) {
            double multiplier = tierMultipliers.getOrDefault(tier == null ? Tier.SIMPLE : tier, 1.0);
            double raw = cost * Math.pow(escalation, Math.max(0, rolls)) * multiplier;
            if (roundTo > 1) raw = Math.round(raw / roundTo) * (double) roundTo;
            return cost <= 0 ? 0 : Math.max(roundTo > 1 ? roundTo : 0.01, raw);
        }

        static RollPricing from(ConfigurationSection section) {
            if (section == null || section.getKeys(false).isEmpty()) return LEGACY;
            Map<Tier, Double> multipliers = new EnumMap<>(LEGACY.tierMultipliers);
            ConfigurationSection tiers = section.getConfigurationSection("tier-multipliers");
            if (tiers != null) {
                for (Tier tier : Tier.values()) {
                    double value = tiers.getDouble(tier.name(), multipliers.get(tier));
                    if (Double.isFinite(value) && value >= 0) multipliers.put(tier, value);
                }
            }
            double cost = section.getDouble("cost", LEGACY.cost);
            double escalation = section.getDouble("escalation", LEGACY.escalation);
            return new RollPricing(section.getBoolean("enabled", LEGACY.enabled),
                    Double.isFinite(cost) ? Math.max(0, cost) : LEGACY.cost,
                    Double.isFinite(escalation) ? Math.max(1, escalation) : LEGACY.escalation,
                    Math.max(1, section.getLong("round-to", LEGACY.roundTo)), multipliers);
        }
    }

    record BonusDef(String id, String displayMini, String legacyColour, String plainName, double minAmount, double maxAmount, String unit, boolean hasMagnitude) {
        double scaled(int strength) {
            if (!hasMagnitude) return maxAmount;
            double ratio = Math.max(0, Math.min(100, strength)) / 100.0;
            return minAmount + (maxAmount - minAmount) * ratio;
        }

        String formatValue(int strength) {
            double val = scaled(strength);
            String num = formatNumber(val);
            return switch (unit) {
                case "percent" -> "+" + num + "%";
                case "x" -> num + "x";
                case "blocks" -> "+" + num + " blocks";
                default -> "+" + num;
            };
        }

        String lineMini(int strength) {
            if (!hasMagnitude) return displayMini;
            return displayMini + " <white>" + formatValue(strength) + "</white>";
        }

        String lineLegacy(int strength) {
            if (!hasMagnitude) return legacyColour + plainName;
            return legacyColour + plainName + " §f" + formatValue(strength);
        }
    }

    record MergeVerdict(boolean allowed, String message, Tier resultTier, String resultId, int mergedQuality) {
    }

    static final List<String> DEFAULT_SIGNATURES = List.of("luck", "strength", "speed");
    static final int BONUS_SLOT_COUNT = 5;
    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    static final Map<String, BonusDef> BONUSES = buildBonusCatalog();
    static final Map<Tier, List<String>> BONUS_POOLS = Map.of(
            Tier.SIMPLE, List.of("crop-boost", "xp-drop-boost"),
            Tier.ELITE, List.of("crop-boost", "xp-drop-boost", "armor", "loot-boost"),
            Tier.ULTIMATE, List.of("crop-boost", "xp-drop-boost", "armor", "loot-boost", "haste", "regeneration"),
            Tier.LEGENDARY, List.of("crop-boost", "xp-drop-boost", "armor", "loot-boost", "haste", "regeneration",
                    "money-quest", "tokens-quest", "scale", "waypoint-range"),
            Tier.FABLED, List.of("crop-boost", "xp-drop-boost", "armor", "loot-boost", "haste", "regeneration",
                    "money-quest", "tokens-quest", "token-drop-chance", "token-drop-multiplier",
                    "scale", "waypoint-range", "waypoint-multiplier", "flight-speed", "disarm-rate", "knockback")
    );

    /**
     * Legacy trading cards' title colours (its bundled {@code cards.yml}), used for any mob the
     * config's {@code card-menu.mob-colours} does not name. Other mobs are white, as in legacy.
     */
    static final Map<String, String> LEGACY_MOB_COLOURS = Map.ofEntries(
            Map.entry("allay", "aqua"),
            Map.entry("axolotl", "dark_aqua"),
            Map.entry("bee", "gold"),
            Map.entry("blaze", "gold"),
            Map.entry("chicken", "yellow"),
            Map.entry("cow", "gray"),
            Map.entry("creeper", "dark_green"),
            Map.entry("dolphin", "dark_aqua"),
            Map.entry("enderman", "dark_purple"),
            Map.entry("fox", "gold"),
            Map.entry("ghast", "white"),
            Map.entry("goat", "gray"),
            Map.entry("hoglin", "dark_purple"),
            Map.entry("horse", "gold"),
            Map.entry("husk", "dark_purple"),
            Map.entry("magma_cube", "gold"),
            Map.entry("mooshroom", "light_purple"),
            Map.entry("ocelot", "yellow"),
            Map.entry("parrot", "yellow"),
            Map.entry("pig", "light_purple"),
            Map.entry("piglin", "light_purple"),
            Map.entry("piglin_brute", "dark_purple"),
            Map.entry("pillager", "gold"),
            Map.entry("pufferfish", "gold"),
            Map.entry("ravager", "dark_purple"),
            Map.entry("sheep", "white"),
            Map.entry("skeleton", "gray"),
            Map.entry("slime", "green"),
            Map.entry("stray", "white"),
            Map.entry("turtle", "dark_green"),
            Map.entry("vex", "light_purple"),
            Map.entry("villager", "white"),
            Map.entry("witch", "dark_purple"),
            Map.entry("wither_skeleton", "dark_gray"),
            Map.entry("wolf", "gray"),
            Map.entry("zombie", "dark_green"));

    final boolean allowDrop;
    /** Legacy's action bar naming the held card. */
    final boolean heldActionBar;
    final boolean dropToInventory;
    final String separator;
    final int xpBarWidth;
    final TreeMap<Integer, Double> xpBreakpoints;
    final List<QualityBand> qualityBands;
    final Map<String, String> mobColours;
    final RollPricing rollPricing;

    CardRules(ConfigurationSection rootConfig) {
        ConfigurationSection section = rootConfig == null ? null : rootConfig.getConfigurationSection("card-menu");
        this.allowDrop = section == null || section.getBoolean("allow-drop", true);
        this.heldActionBar = section == null || section.getBoolean("held-card-action-bar", true);
        this.dropToInventory = section == null || !"ground".equalsIgnoreCase(section.getString("drop-delivery", "inventory"));
        this.separator = section == null ? "───────────────" : section.getString("lore-separator", "───────────────");
        this.xpBarWidth = section == null ? 12 : Math.max(4, Math.min(24, section.getInt("xp-bar-width", 12)));

        TreeMap<Integer, Double> curve = new TreeMap<>();
        ConfigurationSection curveSection = section == null ? null : section.getConfigurationSection("level-curve");
        if (curveSection != null && !curveSection.getKeys(false).isEmpty()) {
            for (String key : curveSection.getKeys(false)) {
                try {
                    int lvl = Integer.parseInt(key);
                    double needed = curveSection.getDouble(key, 100.0);
                    if (lvl > 0 && needed > 0) curve.put(lvl, needed);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (curve.isEmpty()) {
            curve.put(10, 25.0);
            curve.put(25, 120.0);
            curve.put(50, 600.0);
            curve.put(75, 2400.0);
            curve.put(100, 9000.0);
        }
        this.xpBreakpoints = curve;

        List<QualityBand> bands = new ArrayList<>();
        ConfigurationSection bandSection = section == null ? null : section.getConfigurationSection("quality-bands");
        if (bandSection != null && !bandSection.getKeys(false).isEmpty()) {
            for (String id : bandSection.getKeys(false)) {
                ConfigurationSection row = bandSection.getConfigurationSection(id);
                if (row == null) continue;
                int min = row.getInt("min", 1);
                int max = row.getInt("max", 100);
                int heads = row.getInt("heads", 1);
                String colour = row.getString("colour", "white").replace("<", "").replace(">", "").trim();
                List<Integer> boost = row.getIntegerList("boost");
                int bMin = boost.size() >= 1 ? boost.get(0) : 1;
                int bMax = boost.size() >= 2 ? boost.get(1) : bMin;
                bands.add(new QualityBand(id, min, max, heads, colour, bMin, bMax));
            }
        }
        if (bands.isEmpty()) {
            bands = List.of(
                    new QualityBand("rotten", 1, 5, 1, "dark_gray", 1, 2),
                    new QualityBand("damaged", 6, 15, 1, "gray", 2, 3),
                    new QualityBand("torn", 16, 25, 2, "dark_red", 3, 4),
                    new QualityBand("okay", 26, 45, 3, "white", 4, 5),
                    new QualityBand("fine", 46, 65, 5, "yellow", 5, 6),
                    new QualityBand("great", 66, 85, 6, "green", 6, 7),
                    new QualityBand("mint", 86, 99, 7, "aqua", 7, 9),
                    new QualityBand("emaculate", 100, 100, 8, "gold", 7, 11)
            );
        }
        this.qualityBands = List.copyOf(bands);

        Map<String, String> colours = new LinkedHashMap<>(LEGACY_MOB_COLOURS);
        ConfigurationSection colourSection = section == null ? null : section.getConfigurationSection("mob-colours");
        if (colourSection != null) {
            for (String key : colourSection.getKeys(false)) {
                colours.put(key.toLowerCase(Locale.ROOT), colourSection.getString(key, "white"));
            }
        }
        this.mobColours = Collections.unmodifiableMap(colours);
        this.rollPricing = RollPricing.from(section == null ? null : section.getConfigurationSection("bonus-slot-roll"));
    }

    String mobColour(String mob) {
        if (mob == null || mob.isBlank()) return "white";
        return mobColours.getOrDefault(mob.toLowerCase(Locale.ROOT), "white");
    }

    QualityBand bandFor(int quality) {
        int clamped = Math.max(1, Math.min(100, quality));
        for (QualityBand band : qualityBands) {
            if (band.contains(clamped)) return band;
        }
        return qualityBands.get(0);
    }

    double xpForLevel(int level) {
        int target = Math.max(1, level + 1);
        Map.Entry<Integer, Double> ceil = xpBreakpoints.ceilingEntry(target);
        if (ceil != null) return ceil.getValue();
        return xpBreakpoints.lastEntry().getValue();
    }

    Progression awardCardXp(int level, double xp, double gained, int maxLevel) {
        if (gained <= 0 || level >= maxLevel) {
            return new Progression(Math.min(level, maxLevel), level >= maxLevel ? 0.0 : Math.max(0.0, xp));
        }
        int curLevel = Math.max(0, level);
        double curXp = Math.max(0.0, xp) + gained;
        while (curLevel < maxLevel) {
            double needed = xpForLevel(curLevel);
            if (curXp < needed) break;
            curXp -= needed;
            curLevel++;
        }
        if (curLevel >= maxLevel) {
            curLevel = maxLevel;
            curXp = 0.0;
        }
        return new Progression(curLevel, Math.round(curXp * 100.0) / 100.0);
    }

    /** Deterministic starting signature boosts matching SF's StartingBoosts seed formula. */
    List<StartingValue> startingValues(String cardId, Tier tier, int quality, int level, List<String> signatures) {
        if (signatures == null || signatures.isEmpty()) return List.of();
        int n = signatures.size();
        long seed = stableSeed(cardId, tier, quality);
        Random rng = new Random(seed);
        QualityBand band = bandFor(quality);
        int range = Math.max(0, band.boostMax() - band.boostMin());
        double[] base = new double[n];
        for (int i = 0; i < n; i++) {
            base[i] = band.boostMin() + (range == 0 ? 0 : rng.nextInt(range + 1));
        }
        int tierBonus = (tier == null ? Tier.SIMPLE : tier).bonusPoints;
        for (int i = 0; i < tierBonus; i++) {
            base[rng.nextInt(n)] += 1.0;
        }
        int[] points = new int[n];
        int clampedLevel = Math.max(0, level);
        for (int i = 0; i < clampedLevel; i++) {
            points[rng.nextInt(n)]++;
        }
        List<StartingValue> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new StartingValue(signatures.get(i), base[i], points[i]));
        }
        return out;
    }

    private static long stableSeed(String cardId, Tier tier, int quality) {
        String key = (cardId == null ? "" : cardId.toLowerCase(Locale.ROOT))
                + "|" + (tier == null ? Tier.SIMPLE : tier).name()
                + "|" + quality;
        long h = 1125899906842597L;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h = 31L * h + (b & 0xff);
        }
        return h;
    }

    static List<String> parseSignatures(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_SIGNATURES;
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out.isEmpty() ? DEFAULT_SIGNATURES : out;
    }

    static List<BonusSlotData> parseBonuses(String raw) {
        List<BonusSlotData> out = new ArrayList<>(BONUS_SLOT_COUNT);
        if (raw != null && !raw.isBlank()) {
            String[] parts = raw.split(",", -1);
            for (int i = 0; i < Math.min(BONUS_SLOT_COUNT, parts.length); i++) {
                out.add(parseBonusToken(parts[i]));
            }
        }
        while (out.size() < BONUS_SLOT_COUNT) out.add(BonusSlotData.EMPTY);
        return out;
    }

    private static BonusSlotData parseBonusToken(String token) {
        if (token == null || token.isBlank()) return BonusSlotData.EMPTY;
        String[] fields = token.split(":", -1);
        String id = fields[0].trim().toLowerCase(Locale.ROOT);
        double spent = fields.length > 4 ? parseDoubleSafe(fields[4]) : 0;
        if (id.isEmpty()) {
            // A cleared slot keeps its roll count and spend.
            int rolls = fields.length > 3 ? parseIntSafe(fields[3], 0) : 0;
            return new BonusSlotData("", 0, false, Math.max(0, rolls), spent);
        }
        int strength = fields.length > 1 ? parseIntSafe(fields[1], 50) : 50;
        boolean locked = fields.length > 2 && "L".equalsIgnoreCase(fields[2].trim());
        int rolls = fields.length > 3 ? parseIntSafe(fields[3], 1) : 1;
        return new BonusSlotData(id, Math.max(1, Math.min(100, strength)), locked, Math.max(0, rolls), spent);
    }

    static String encodeBonuses(List<BonusSlotData> slots) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < BONUS_SLOT_COUNT; i++) {
            if (i > 0) sb.append(',');
            BonusSlotData s = i < slots.size() ? slots.get(i) : BonusSlotData.EMPTY;
            if (!s.isEmpty()) {
                sb.append(s.boostId()).append(':').append(s.strength()).append(':')
                        .append(s.locked() ? 'L' : 'U').append(':').append(s.rolls());
                if (s.spent() > 0) sb.append(':').append(money(s.spent()));
            } else if (s.rolls() > 0 || s.spent() > 0) {
                sb.append(":0:U:").append(s.rolls());
                if (s.spent() > 0) sb.append(':').append(money(s.spent()));
            }
        }
        return sb.toString();
    }

    BonusSlotData rollBonusSlot(Tier tier, List<BonusSlotData> currentSlots, int slotIndex, Random rng) {
        List<String> pool = BONUS_POOLS.getOrDefault(tier == null ? Tier.SIMPLE : tier, BONUS_POOLS.get(Tier.SIMPLE));
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < currentSlots.size(); i++) {
            if (i != slotIndex && !currentSlots.get(i).isEmpty()) {
                existing.add(currentSlots.get(i).boostId());
            }
        }
        String currentId = slotIndex < currentSlots.size() ? currentSlots.get(slotIndex).boostId() : "";
        List<String> candidates = pool.stream().filter(id -> !existing.contains(id) && !id.equals(currentId)).toList();
        if (candidates.isEmpty()) {
            candidates = pool.stream().filter(id -> !existing.contains(id)).toList();
        }
        if (candidates.isEmpty()) return null;
        String picked = candidates.get(rng.nextInt(candidates.size()));
        int strength = 1 + rng.nextInt(100);
        int prevRolls = slotIndex < currentSlots.size() ? currentSlots.get(slotIndex).rolls() : 0;
        double prevSpent = slotIndex < currentSlots.size() ? currentSlots.get(slotIndex).spent() : 0;
        return new BonusSlotData(picked, strength, false, prevRolls + 1, prevSpent);
    }

    int rollQuality(Random rng) {
        return 1 + (rng == null ? ThreadLocalRandom.current() : rng).nextInt(100);
    }

    MergeVerdict checkMerge(Catalog.Entry first, int firstLevel, int firstQuality,
                            Catalog.Entry second, int secondLevel, int secondQuality,
                            Map<String, Catalog.Entry> allEntries) {
        if (first == null || second == null) {
            return new MergeVerdict(false, "Select a second matching card from your inventory.", null, null, 0);
        }
        if (first.legacyCard().isEmpty() || second.legacyCard().isEmpty()) {
            return new MergeVerdict(false, "Only trading cards can be forged to a higher tier.", null, null, 0);
        }
        if (!first.legacyCard().equalsIgnoreCase(second.legacyCard())) {
            return new MergeVerdict(false, "Those are different mobs — " + titleCase(first.legacyCard())
                    + " and " + titleCase(second.legacyCard()) + ".", null, null, 0);
        }
        Tier t1 = Tier.parse(first.legacyTier());
        Tier t2 = Tier.parse(second.legacyTier());
        if (t1 != t2) {
            return new MergeVerdict(false, "Both cards must be the same tier — those are " + t1 + " and " + t2 + ".", null, null, 0);
        }
        if (firstLevel < first.maxLevel() || secondLevel < second.maxLevel()) {
            return new MergeVerdict(false, "Both cards must be level " + first.maxLevel() + " first — they are level "
                    + firstLevel + " and " + secondLevel + ".", null, null, 0);
        }
        Tier next = t1.next();
        if (next == null) {
            return new MergeVerdict(false, t1 + " is the highest tier, so there is nothing to merge into.", null, null, 0);
        }
        String resultId = "card_" + first.legacyCard().toLowerCase(Locale.ROOT) + "_" + next.name().toLowerCase(Locale.ROOT);
        if (!allEntries.containsKey(resultId)) {
            return new MergeVerdict(false, "Next tier definition " + resultId + " is unavailable.", null, null, 0);
        }
        return new MergeVerdict(true, "Forge into a " + titleCase(next.name()) + " card!", next, resultId, Math.max(firstQuality, secondQuality));
    }

    static String smallCaps(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        boolean tag = false;
        boolean command = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!tag && c == '<' && (i == 0 || text.charAt(i - 1) != '\\')) {
                tag = true;
                command = false;
            } else if (tag) {
                if (quote != 0) {
                    if (c == quote && text.charAt(i - 1) != '\\') quote = 0;
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '>') {
                    tag = false;
                    out.append(c);
                    continue;
                }
            } else if (c == '§' && i + 1 < text.length()) {
                out.append(c).append(text.charAt(++i));
                continue;
            } else if (c == '/') {
                command = true;
            } else if (Character.isWhitespace(c)) {
                command = false;
            }
            char lower = Character.toLowerCase(c);
            out.append(!tag && !command && lower >= 'a' && lower <= 'z' ? SMALL_CAPS.charAt(lower - 'a') : c);
        }
        return out.toString();
    }

    static String titleCase(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] words = raw.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    static String legacyColourCode(String name) {
        if (name == null) return "§f";
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "black" -> "§0";
            case "dark_blue" -> "§1";
            case "dark_green" -> "§2";
            case "dark_aqua" -> "§3";
            case "dark_red" -> "§4";
            case "dark_purple" -> "§5";
            case "gold" -> "§6";
            case "gray" -> "§7";
            case "dark_gray" -> "§8";
            case "blue" -> "§9";
            case "green" -> "§a";
            case "aqua" -> "§b";
            case "red" -> "§c";
            case "light_purple" -> "§d";
            case "yellow" -> "§e";
            default -> "§f";
        };
    }

    static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-6) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** An amount of money as plain text with at most two decimals, for the card's state. */
    private static String money(double amount) {
        return java.math.BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_EVEN)
                .stripTrailingZeros().toPlainString();
    }

    private static double parseDoubleSafe(String raw) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? Math.max(0, value) : 0;
        } catch (NumberFormatException | NullPointerException ignored) {
            return 0;
        }
    }

    static int parseIntSafe(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Map<String, BonusDef> buildBonusCatalog() {
        Map<String, BonusDef> m = new LinkedHashMap<>();
        // Signature AuraSkills stats
        m.put("luck", new BonusDef("luck", "<green>Luck", "§a", "Luck", 1, 10, "flat", true));
        m.put("strength", new BonusDef("strength", "<red>Strength", "§c", "Strength", 1, 10, "flat", true));
        m.put("speed", new BonusDef("speed", "<yellow>Speed", "§e", "Speed", 1, 10, "flat", true));
        m.put("health", new BonusDef("health", "<red>Health", "§c", "Health", 1, 10, "flat", true));
        m.put("toughness", new BonusDef("toughness", "<dark_gray>Toughness", "§8", "Toughness", 1, 10, "flat", true));
        m.put("wisdom", new BonusDef("wisdom", "<aqua>Wisdom", "§b", "Wisdom", 1, 10, "flat", true));
        m.put("regeneration", new BonusDef("regeneration", "<light_purple>Regeneration", "§d", "Regeneration", 0.5, 3.0, "flat", true));
        m.put("crit-chance", new BonusDef("crit-chance", "<gold>Crit Chance", "§6", "Crit Chance", 1, 5, "flat", true));
        m.put("crit-damage", new BonusDef("crit-damage", "<yellow>Crit Damage", "§e", "Crit Damage", 1, 10, "flat", true));
        // Bonus boosts matching SF boosts.yml
        m.put("crop-boost", new BonusDef("crop-boost", "<green>Crop Quality", "§a", "Crop Quality", 5, 25, "percent", true));
        m.put("xp-drop-boost", new BonusDef("xp-drop-boost", "<aqua>Experience", "§b", "Experience", 5, 30, "percent", true));
        m.put("armor", new BonusDef("armor", "<white>Armor", "§f", "Armor", 1, 4, "flat", true));
        m.put("loot-boost", new BonusDef("loot-boost", "<white>Looting", "§f", "Looting", 1, 5, "flat", true));
        m.put("haste", new BonusDef("haste", "<gold>Haste", "§6", "Haste", 5, 25, "percent", true));
        m.put("money-quest", new BonusDef("money-quest", "<gold>Quest Money", "§6", "Quest Money", 5, 25, "percent", true));
        m.put("tokens-quest", new BonusDef("tokens-quest", "<yellow>Quest Tokens", "§e", "Quest Tokens", 5, 25, "percent", true));
        m.put("token-drop-chance", new BonusDef("token-drop-chance", "<yellow>Token Chance", "§e", "Token Chance", 5, 25, "percent", true));
        m.put("token-drop-multiplier", new BonusDef("token-drop-multiplier", "<gold>Token Multiplier", "§6", "Token Multiplier", 1.1, 1.5, "x", true));
        m.put("scale", new BonusDef("scale", "<gold>Player Scale", "§6", "Player Scale", 0.8, 1.25, "x", true));
        m.put("waypoint-range", new BonusDef("waypoint-range", "<aqua>Transmit Range", "§b", "Transmit Range", 16, 64, "blocks", true));
        m.put("waypoint-multiplier", new BonusDef("waypoint-multiplier", "<aqua>Transmit Reach", "§b", "Transmit Reach", 1.2, 2.0, "x", true));
        m.put("flight-speed", new BonusDef("flight-speed", "<aqua>Flight Speed", "§b", "Flight Speed", 1.1, 1.6, "x", true));
        m.put("disarm-rate", new BonusDef("disarm-rate", "<dark_purple>Disarm Rate", "§5", "Disarm Rate", 5, 15, "percent", true));
        m.put("knockback", new BonusDef("knockback", "<gray>Knockback Resistance", "§7", "Knockback Resistance", 5, 20, "percent", true));
        return Collections.unmodifiableMap(m);
    }
}
