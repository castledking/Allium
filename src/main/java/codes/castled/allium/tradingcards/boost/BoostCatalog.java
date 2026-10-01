package codes.castled.allium.tradingcards.boost;

import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.config.ValidationIssue;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses {@code tradingcards/boosts.yml} into the boost catalogue.
 *
 * <p>Boosts are declared once here and referenced by id from card definitions,
 * so adding a boost to every card is one edit per card and a new card that
 * reuses an existing boost needs no boost configuration at all.
 *
 * <p>A boost that maps to an AuraSkills stat is marked incrementable
 * automatically, because a signature boost's whole contract is that levelling's
 * +1 means something in its unit. Boosts declared with a different mechanism
 * are not eligible and are reported as errors if listed as signatures.
 */
public final class BoostCatalog {

    public static final String FILE = "boosts.yml";

    private BoostCatalog() {}

    /** Result of one load pass. */
    public record LoadResult(
        Map<String, BoostDefinition> boosts,
        Map<String, Double> signatureAmounts,
        Map<Tier, List<String>> signaturePool,
        Map<Tier, List<String>> bonusPool,
        int bonusRollCount,
        double signatureUnlockChance,
        int maximumSignatures,
        List<ValidationIssue> issues
    ) {
        public boolean hasErrors() {
            return issues.stream().anyMatch(ValidationIssue::isError);
        }
    }

    public static LoadResult load(ConfigurationSection yaml,
                                  int defaultRollCount,
                                  double defaultUnlockChance,
                                  int defaultMaxSignatures) {
        Map<String, BoostDefinition> boosts = new LinkedHashMap<>();
        List<ValidationIssue> issues = new ArrayList<>();

        if (yaml == null) {
            issues.add(ValidationIssue.error(FILE, "",
                "File is missing or unreadable; no boosts are available"));
            return new LoadResult(boosts, Map.of(), Map.of(), Map.of(),
                defaultRollCount, defaultUnlockChance, defaultMaxSignatures, issues);
        }

        // Amounts are read separately from the definitions: they are the
        // numbers a card's lore shows, and the definition is what applies them.
        Map<String, Double> amounts = readAmounts(
            yaml.getConfigurationSection("signature-amounts"), issues);

        ConfigurationSection section = yaml.getConfigurationSection("boosts");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String path = "boosts." + id;
                ConfigurationSection entry = section.getConfigurationSection(id);
                if (entry == null) {
                    issues.add(ValidationIssue.error(FILE, path,
                        "Boost is not a section"));
                    continue;
                }
                BoostMechanism mechanism = BoostDefinition.parseMechanism(
                    entry.getString("mechanism"));
                if (mechanism == null) {
                    issues.add(ValidationIssue.error(FILE, path + ".mechanism",
                        "Unknown mechanism '" + entry.getString("mechanism")
                            + "'; expected one of " + java.util.Arrays.toString(
                                BoostMechanism.values())));
                    continue;
                }
                if (mechanism == BoostMechanism.ATTRIBUTE
                    && !isKnownAttribute(entry.getString("attribute"))) {
                    issues.add(ValidationIssue.error(FILE, path + ".attribute",
                        "Unknown attribute '" + entry.getString("attribute")
                            + "'. Known: " + String.join(", ", KNOWN_ATTRIBUTES)));
                    continue;
                }
                double amount = entry.getDouble("amount", 0.0);
                String normalisedId = id.toLowerCase(Locale.ROOT);
                if (amount <= 0.0) {
                    // An AuraSkills signature takes its amount from
                    // signature-amounts, which is where the lore numbers live.
                    // Declaring it in both places would be two numbers that can
                    // disagree, so the block entry only names the mechanism.
                    amount = amounts.getOrDefault(normalisedId, 0.0);
                }
                if (amount <= 0.0) {
                    issues.add(ValidationIssue.error(FILE, path + ".amount",
                        "Boost has no amount and none under signature-amounts, so its "
                            + "lore would show nothing"));
                    continue;
                }
                double secondary = entry.getDouble("secondary", 0.0);
                Map<String, String> options = new LinkedHashMap<>();
                for (String key : entry.getKeys(false)) {
                    // `attribute` and `stat` are kept: they are the lookup keys
                    // the applier needs, not display settings. Only the fields
                    // already promoted to record components are dropped.
                    if (key.equals("display") || key.equals("mechanism")
                        || key.equals("amount") || key.equals("secondary")) {
                        continue;
                    }
                    Object raw = entry.get(key);
                    if (raw != null && !(raw instanceof org.bukkit.configuration.ConfigurationSection)) {
                        options.put(key, String.valueOf(raw));
                    }
                }
                boolean incrementable = mechanism.canBeSignature();
                boosts.put(id, new BoostDefinition(
                    id,
                    entry.getString("display", id),
                    mechanism,
                    amount,
                    secondary,
                    incrementable,
                    options));
            }
        }

        Map<Tier, List<String>> signaturePool = readIdList(
            yaml.getConfigurationSection("signature-pool"), "signature-pool",
            boosts, issues);
        Map<Tier, List<String>> bonusPool = readIdList(
            yaml.getConfigurationSection("bonus"), "bonus", boosts, issues);

        int rollCount = yaml.getInt("roll.count", defaultRollCount);
        if (rollCount < 1) {
            issues.add(ValidationIssue.warning(FILE, "roll.count",
                "Below 1; every card would roll no bonus boosts. Using 1."));
            rollCount = 1;
        }
        double unlockChance = yaml.getDouble("signature-unlock-chance", defaultUnlockChance);
        if (unlockChance < 0.0 || unlockChance > 1.0) {
            issues.add(ValidationIssue.warning(FILE, "signature-unlock-chance",
                "Must be in (0,1], got " + unlockChance + "; using 0.35"));
            unlockChance = 0.35;
        }
        int maxSignatures = yaml.getInt("maximum-signatures", defaultMaxSignatures);
        if (maxSignatures < 3) {
            // 3 is where every card starts; below that a card would have to
            // lose a signature to equip, which nothing supports.
            issues.add(ValidationIssue.warning(FILE, "maximum-signatures",
                "Below 3, which is where every card starts. Using 3."));
            maxSignatures = 3;
        }

        return new LoadResult(boosts, amounts, signaturePool, bonusPool,
            rollCount, unlockChance, maxSignatures, issues);
    }

    private static Map<String, Double> readAmounts(ConfigurationSection section,
                                                   List<ValidationIssue> issues) {
        Map<String, Double> amounts = new LinkedHashMap<>();
        if (section == null) return amounts;
        for (String key : section.getKeys(false)) {
            double value = section.getDouble(key, 0.0);
            if (value <= 0.0) {
                issues.add(ValidationIssue.warning(FILE, "signature-amounts." + key,
                    "Amount must be positive, got " + value + "; using 1.0"));
                value = 1.0;
            }
            amounts.put(key.toLowerCase(Locale.ROOT), value);
        }
        return amounts;
    }

    private static Map<Tier, List<String>> readIdList(ConfigurationSection section,
                                                     String base,
                                                     Map<String, BoostDefinition> boosts,
                                                     List<ValidationIssue> issues) {
        Map<Tier, List<String>> out = new EnumMap<>(Tier.class);
        if (section == null) {
            issues.add(ValidationIssue.error(FILE, base,
                "No '" + base + "' section; no card can roll a boost"));
            return out;
        }
        for (String tierKey : section.getKeys(false)) {
            Tier tier = codes.castled.allium.tradingcards.card.CardDefinition.parseTier(tierKey);
            if (tier == null) {
                issues.add(ValidationIssue.error(FILE, base + "." + tierKey,
                    "Unknown tier '" + tierKey + "'"));
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (String raw : section.getStringList(tierKey)) {
                String id = raw.trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty()) continue;
                BoostDefinition boost = boosts.get(id);
                if (boost == null) {
                    issues.add(ValidationIssue.error(FILE, base + "." + tierKey,
                        "Unknown boost '" + id + "'; it is not in the catalogue"));
                    continue;
                }
                if (!boost.isSignature() && base.equals("signature-pool")) {
                    // A non-incrementable boost cannot be a signature: levelling
                    // adds +1 to a signature, and "+0.1x player scale" is not a
                    // quantity, so the card would show a meaningless number.
                    issues.add(ValidationIssue.error(FILE, base + "." + tierKey,
                        "Boost '" + id + "' uses " + boost.mechanism()
                            + ", which is not incrementable, so it cannot be a "
                            + "signature. It belongs in the bonus list."));
                    continue;
                }
                ids.add(id);
            }
            if (ids.isEmpty()) {
                issues.add(ValidationIssue.warning(FILE, base + "." + tierKey,
                    "Tier has no usable boost ids"));
            }
            out.put(tier, List.copyOf(ids));
        }
        for (Tier tier : Tier.values()) {
            if (!out.containsKey(tier)) {
                issues.add(ValidationIssue.warning(FILE, base,
                    "No entry for " + tier + "; that tier can roll nothing"));
            }
        }
        return out;
    }

    /**
     * The vanilla attributes a boost may target, by name.
     *
     * <p>A plain list rather than {@code Attribute.values()} so the catalogue
     * can be loaded and tested without a live server: touching Bukkit's
     * Attribute enum pulls in its registry, which has no class initialiser
     * outside a running server. The name is resolved to the enum at apply
     * time, when one exists.
     */
    public static final List<String> KNOWN_ATTRIBUTES = List.of(
        "ARMOR", "ATTACK_SPEED", "MAX_HEALTH", "MOVEMENT_SPEED",
        "KNOCKBACK_RESISTANCE", "LUCK", "GRAVITY", "SAFE_FALL_DISTANCE",
        "JUMP_STRENGTH", "STEP_HEIGHT", "FALL_DAMAGE_MULTIPLIER",
        "FLYING_SPEED", "SCALE", "BLOCK_INTERACTION_RANGE",
        "ENTITY_INTERACTION_RANGE", "ATTACK_DAMAGE", "ARMOR_TOUGHNESS",
        "MAX_ABSORPTION", "OXYGEN_BONUS", "WATER_MOVEMENT_EFFICIENCY");

    /** True when a configured attribute names one this build knows. */
    public static boolean isKnownAttribute(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String trimmed = raw.trim();
        for (String known : KNOWN_ATTRIBUTES) {
            if (known.equalsIgnoreCase(trimmed)) return true;
        }
        // Accept the namespaced form too, so "minecraft:attack_speed" works.
        int colon = trimmed.indexOf(':');
        if (colon >= 0) {
            return isKnownAttribute(trimmed.substring(colon + 1));
        }
        return false;
    }
}
