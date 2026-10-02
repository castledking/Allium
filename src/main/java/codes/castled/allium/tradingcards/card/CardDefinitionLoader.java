package codes.castled.allium.tradingcards.card;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.tradingcards.config.ValidationIssue;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses {@code tradingcards/cards.yml} into immutable card definitions,
 * collecting every problem instead of failing fast.
 *
 * <p>Parsing never throws. A card with a fatal problem is skipped and the rest
 * of the file still loads, so one broken entry does not take the other 37 mobs
 * with it — the same best-effort rule the crop loader follows.
 *
 * <p>Item existence is checked through a supplied predicate rather than by
 * touching Nexo directly, so the loader can be tested without a running server
 * or a Nexo installation.
 */
public final class CardDefinitionLoader {

    /** Config file this loader reads, used in validation messages. */
    public static final String FILE = "cards.yml";

    /** Result of one load pass. */
    public record LoadResult(
        Map<String, CardDefinition> cards,
        List<ValidationIssue> issues
    ) {
        public boolean hasErrors() {
            return issues.stream().anyMatch(ValidationIssue::isError);
        }
    }

    private final Predicate<String> knownNamespace;
    private final Predicate<ItemRef> itemExists;

    public CardDefinitionLoader(Predicate<String> knownNamespace, Predicate<ItemRef> itemExists) {
        this.knownNamespace = knownNamespace;
        this.itemExists = itemExists;
    }

    /**
     * Loads every card under the {@code cards} section.
     *
     * @param yaml the parsed file, or null when the file is absent
     */
    public LoadResult load(ConfigurationSection yaml) {
        Map<String, CardDefinition> cards = new LinkedHashMap<>();
        List<ValidationIssue> issues = new ArrayList<>();

        if (yaml == null) {
            issues.add(ValidationIssue.error(FILE, "cards",
                "File is missing or unreadable; no cards loaded"));
            return new LoadResult(cards, issues);
        }

        ConfigurationSection section = yaml.getConfigurationSection("cards");
        if (section == null) {
            issues.add(ValidationIssue.error(FILE, "cards",
                "No 'cards' section; the file parsed but defines nothing"));
            return new LoadResult(cards, issues);
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            String base = "cards." + key;
            if (entry == null) {
                issues.add(ValidationIssue.error(FILE, base, "Card is not a section"));
                continue;
            }
            int before = errorCount(issues);
            CardDefinition card = parseCard(key, entry, base, issues);
            // A card that produced errors is dropped wholesale rather than
            // registered half-configured: a card with a missing tier item would
            // otherwise drop an item nobody can hold.
            if (card == null || errorCount(issues) > before) {
                continue;
            }
            if (cards.containsKey(card.id())) {
                issues.add(ValidationIssue.error(FILE, base,
                    "Duplicate card id '" + card.id() + "'"));
                continue;
            }
            cards.put(card.id(), card);
        }
        return new LoadResult(cards, issues);
    }

    private CardDefinition parseCard(String key, ConfigurationSection section, String base,
                                     List<ValidationIssue> issues) {
        String id = section.getString("id", key).trim().toLowerCase(Locale.ROOT);
        if (id.isBlank()) {
            issues.add(ValidationIssue.error(FILE, base + ".id", "Card id is blank"));
            return null;
        }

        String mob = section.getString("mob");
        if (mob == null || mob.isBlank()) {
            issues.add(ValidationIssue.error(FILE, base + ".mob",
                "Card '" + id + "' has no mob; the drop listener matches on this"));
            return null;
        }
        mob = mob.trim().toUpperCase(Locale.ROOT);

        double chance = section.getDouble("chance", 0.0);
        if (chance <= 0.0 || chance > 1.0) {
            issues.add(ValidationIssue.error(FILE, base + ".chance",
                "Drop chance must be in (0,1], got " + chance
                    + ". Set 0 to stop the mob dropping cards entirely."));
        }

        Map<Tier, Double> tiers = new EnumMap<>(Tier.class);
        Map<Tier, ItemRef> items = new EnumMap<>(Tier.class);
        ConfigurationSection tierSection = section.getConfigurationSection("tiers");
        if (tierSection == null) {
            issues.add(ValidationIssue.error(FILE, base + ".tiers",
                "Card '" + id + "' has no tiers section"));
            return null;
        }

        for (String tierKey : tierSection.getKeys(false)) {
            String tierBase = base + ".tiers." + tierKey;
            Tier tier = CardDefinition.parseTier(tierKey);
            if (tier == null) {
                issues.add(ValidationIssue.error(FILE, tierBase,
                    "Unknown tier '" + tierKey + "'; expected one of "
                        + java.util.Arrays.toString(Tier.values())));
                continue;
            }
            ConfigurationSection tierDef = tierSection.getConfigurationSection(tierKey);
            double weight;
            if (tierDef == null) {
                // Tolerate a bare number, which is a common shorthand for a
                // weight on the only value a tier entry strictly needs. It falls
                // through to the same item check below rather than skipping it,
                // so a bare positive weight is reported for having no item
                // instead of silently registering a tier that drops nothing.
                Object raw = tierSection.get(tierKey);
                if (!(raw instanceof Number number)) {
                    issues.add(ValidationIssue.error(FILE, tierBase,
                        "Tier is neither a section nor a number"));
                    continue;
                }
                weight = Math.max(0.0, number.doubleValue());
            } else {
                weight = tierDef.getDouble("weight", 0.0);
                if (weight < 0.0) {
                    issues.add(ValidationIssue.error(FILE, tierBase + ".weight",
                        "Negative tier weight; set 0 to exclude the tier"));
                    weight = 0.0;
                }
            }
            tiers.put(tier, weight);

            if (weight <= 0.0) {
                continue;
            }

            String itemRaw = tierDef == null ? null : tierDef.getString("item");
            if (itemRaw == null || itemRaw.isBlank()) {
                issues.add(ValidationIssue.error(FILE, tierBase + ".item",
                    "Tier " + tier + " has a positive weight but no item, so it "
                        + "would drop an item nobody can hold"));
                continue;
            }
            ItemRef item = parseItemRef(itemRaw, tierBase + ".item", issues);
            if (item == null) continue;
            if (itemExists != null && !itemExists.test(item)) {
                issues.add(ValidationIssue.error(FILE, tierBase + ".item",
                    "Item '" + item + "' does not exist. A card that drops a missing "
                        + "item is worse than no card at all, so this tier is skipped."));
                continue;
            }
            items.put(tier, item);
        }

        if (tiers.isEmpty()) {
            issues.add(ValidationIssue.error(FILE, base + ".tiers",
                "Card '" + id + "' has no tiers at all"));
            return null;
        }
        if (tiers.values().stream().noneMatch(w -> w > 0.0)) {
            issues.add(ValidationIssue.error(FILE, base + ".tiers",
                "Card '" + id + "' has no tier with a positive weight, so it can "
                    + "never drop (" + CardDefinition.describeWeights(tiers) + ")"));
        }

        ItemRef head = null;
        String headRaw = section.getString("head");
        if (headRaw != null && !headRaw.isBlank()) {
            head = parseItemRef(headRaw, base + ".head", issues);
        }

        return new CardDefinition(id, mob,
            section.getString("colour"), chance, tiers, items, head);
    }

    private ItemRef parseItemRef(String raw, String path, List<ValidationIssue> issues) {
        try {
            ItemRef ref = ItemRef.parse(raw);
            if (knownNamespace != null && !knownNamespace.test(ref.namespace())) {
                issues.add(ValidationIssue.error(FILE, path,
                    "Unknown item namespace '" + ref.namespace()
                        + "'; expected nexo, oraxen or minecraft"));
                return null;
            }
            return ref;
        } catch (IllegalArgumentException e) {
            issues.add(ValidationIssue.error(FILE, path, e.getMessage()));
            return null;
        }
    }

    private static int errorCount(List<ValidationIssue> issues) {
        return (int) issues.stream().filter(ValidationIssue::isError).count();
    }
}
