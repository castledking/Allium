package codes.castled.curios;

import org.bukkit.Material;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

final class Catalog {
    enum DropCondition { ANY, RAID, FREEZE }
    enum Ability {
        UNARMED_IGNITE, PHANTOM_TRUCE, PIGLIN_TRUCE, ECHO_STRIKE, PURIFY, WATER_RETALIATE,
        SUMMON_VEX, LAVA_WALKER, CHORUS_BLINK, GOLEM_KNOCKBACK_IMMUNITY
    }
    record AttributeBonus(String attribute, double amount, AttributeModifier.Operation operation) {}
    record Entry(String id, String name, String slot, int model, int maxLevel, int step, String frame,
                 Material trade, int tradeAmount, String dropMob, double chance, DropCondition dropCondition, String lootTable, String legacyRelic,
                 String legacyCard, String legacyTier, ConfigurationSection recipe, Set<Ability> abilities,
                 List<AttributeBonus> attributes, Map<String, Double> effects) {
        double effect(String key, double fallback) {
            Double value = effects.get(key);
            return value == null || !Double.isFinite(value) ? fallback : value;
        }
        int effectTicks(String key, int fallback) {
            return (int) Math.round(effect(key, fallback));
        }
    }
    record Slot(String type, int index) {
        String key() { return type + "_" + index; }
    }
    record Pool(String mob, double chance, Map<Entry, Double> weights) {
        Entry roll(double sample) {
            double cursor = sample * weights.values().stream().mapToDouble(Double::doubleValue).sum();
            for (var choice : weights.entrySet()) {
                cursor -= choice.getValue();
                if (cursor < 0) return choice.getKey();
            }
            return null;
        }
    }

    final Map<String, Entry> entries = new LinkedHashMap<>();
    final List<Slot> slots = new ArrayList<>();
    final List<Pool> pools = new ArrayList<>();
    final int killXp;

    Catalog(ConfigurationSection config) {
        killXp = integer(config, "kill-xp", 0, 100_000);
        var slotConfig = section(config, "slots");
        for (String slot : slotConfig.getKeys(false)) {
            requireId(slot);
            for (int i = 0; i < integer(slotConfig, slot, 0, 32); i++) slots.add(new Slot(slot, i));
        }
        if (slots.size() > 56) throw new IllegalArgumentException("Curios supports at most 56 equipment slots");
        var items = section(config, "items");
        Set<Integer> modelIds = new HashSet<>();
        for (String id : items.getKeys(false)) {
            requireId(id);
            var row = section(items, id);
            String slot = row.getString("slot", "");
            if (!slotConfig.contains(slot)) throw new IllegalArgumentException(id + ": unknown slot " + slot);
            int model = integer(row, "model", 1, Integer.MAX_VALUE);
            if (!modelIds.add(model)) throw new IllegalArgumentException(id + ": duplicate model");
            String tradeId = row.getString("trade-item");
            Material trade = tradeId == null ? null : Material.matchMaterial(tradeId);
            if (tradeId != null && (trade == null || !trade.isItem() || trade.isAir())) {
                throw new IllegalArgumentException(id + ": invalid trade-item");
            }
            entries.put(id, new Entry(id, row.getString("name", id), slot, model,
                    integer(row, "max-level", 0, 1000), integer(row, "xp-per-level", 1, 1_000_000),
                    row.getString("frame", "simple"), trade, trade == null ? 0 : integer(row, "trade-amount", 1, trade.getMaxStackSize()),
                    row.getString("drop-mob", ""), chance(row, "drop-chance"),
                    DropCondition.valueOf(row.getString("drop-condition", "ANY").toUpperCase(Locale.ROOT)), row.getString("loot-table", ""), row.getString("legacy-relic", ""),
                    row.getString("legacy-card", ""), row.getString("legacy-tier", ""), row.getConfigurationSection("recipe"), abilities(row),
                    attributes(id, row), effects(row)));
        }
        var cardPools = config.getConfigurationSection("card-pools");
        if (cardPools == null) return;
        for (String id : cardPools.getKeys(false)) {
            var row = section(cardPools, id);
            var weights = section(row, "weights");
            Map<Entry, Double> weighted = new LinkedHashMap<>();
            for (String key : weights.getKeys(false)) {
                Entry entry = entries.get(key);
                double weight = weights.getDouble(key, -1);
                if (entry == null || !Double.isFinite(weight) || weight < 0 || weight > 1e9) {
                    throw new IllegalArgumentException(weights.getCurrentPath() + ": invalid weight for " + key);
                }
                if (weight > 0) weighted.put(entry, weight);
            }
            pools.add(new Pool(row.getString("mob", ""), chance(row, "chance"), Collections.unmodifiableMap(weighted)));
        }
    }

    private static Set<Ability> abilities(ConfigurationSection entry) {
        EnumSet<Ability> abilities = EnumSet.noneOf(Ability.class);
        for (String value : entry.getStringList("abilities")) {
            abilities.add(Ability.valueOf(value.toUpperCase(Locale.ROOT)));
        }
        return Set.copyOf(abilities);
    }

    private static List<AttributeBonus> attributes(String id, ConfigurationSection entry) {
        List<Map<?, ?>> raw = entry.getMapList("attributes");
        if (raw.isEmpty()) return List.of();
        List<AttributeBonus> out = new ArrayList<>(raw.size());
        for (Map<?, ?> row : raw) {
            Object attrObj = row.get("attribute");
            Object amtObj = row.get("amount");
            if (attrObj == null || !(amtObj instanceof Number num)) {
                throw new IllegalArgumentException(id + ": invalid attribute modifier");
            }
            String attr = attrObj.toString().trim();
            double amount = num.doubleValue();
            if (attr.isEmpty() || !Double.isFinite(amount)) {
                throw new IllegalArgumentException(id + ": invalid attribute modifier " + attr);
            }
            String opName = row.get("operation") == null ? "ADD_NUMBER" : row.get("operation").toString().trim().toUpperCase(Locale.ROOT);
            AttributeModifier.Operation op = AttributeModifier.Operation.valueOf(opName);
            out.add(new AttributeBonus(attr, amount, op));
        }
        return List.copyOf(out);
    }

    private static Map<String, Double> effects(ConfigurationSection entry) {
        ConfigurationSection section = entry.getConfigurationSection("effects");
        if (section == null || section.getKeys(false).isEmpty()) return Map.of();
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            double value = section.getDouble(key, Double.NaN);
            if (Double.isFinite(value)) out.put(key, value);
        }
        return Collections.unmodifiableMap(out);
    }

    private static ConfigurationSection section(ConfigurationSection config, String key) {
        var section = config.getConfigurationSection(key);
        if (section == null) throw new IllegalArgumentException("Expected section: " + config.getCurrentPath() + "." + key);
        return section;
    }

    private static int integer(ConfigurationSection section, String key, int min, int max) {
        int value = section.getInt(key, -1);
        if (!section.isInt(key) || value < min || value > max) {
            throw new IllegalArgumentException(section.getCurrentPath() + "." + key + " must be " + min + ".." + max);
        }
        return value;
    }

    private static double chance(ConfigurationSection section, String key) {
        Object raw = section.get(key, 0);
        double result;
        try {
            String value = raw.toString();
            result = value.endsWith("%") ? Double.parseDouble(value.substring(0, value.length() - 1)) / 100
                    : Double.parseDouble(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(section.getCurrentPath() + "." + key + " must be a probability");
        }
        if (!Double.isFinite(result) || result < 0 || result > 1) {
            throw new IllegalArgumentException(section.getCurrentPath() + "." + key + " must be 0..1");
        }
        return result;
    }

    private static void requireId(String id) {
        if (!id.matches("[a-z0-9_]{1,48}")) throw new IllegalArgumentException("Invalid Curios id: " + id);
    }
}
