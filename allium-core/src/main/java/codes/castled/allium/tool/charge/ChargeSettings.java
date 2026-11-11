package codes.castled.allium.tool.charge;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.definition.ConfigReader;
import codes.castled.allium.tool.disarmer.DisarmParameters;
import codes.castled.allium.tool.disarmer.DisarmerRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code charges} block and {@code appearance.charge-variants} that every charged tool shares
 * (the Mob Disarmer, the Phantom Obliterator): the same keys, ranges and rules, so a server reads
 * one format. The charge state itself is {@link codes.castled.allium.tool.disarmer.DisarmerCharges}.
 */
public final class ChargeSettings {

    private static final long MAX_SECONDS = 31_536_000L;

    private ChargeSettings() {
    }

    /**
     * {@code charges.max}, {@code charges.recharge-seconds} and {@code charges.break-chance}
     * ({@code preset: none | legacy | custom}; legacy is 3% per charge and 5% on the final charge).
     */
    public static DisarmerRules rules(ConfigReader root, int defaultMax, long defaultRechargeSeconds) {
        ConfigReader charges = root.section("charges");
        int max = charges.integer("max", defaultMax, 1, 1000);
        long rechargeSeconds = charges.whole("recharge-seconds", defaultRechargeSeconds, 0L, MAX_SECONDS);
        ConfigReader breakChance = charges.section("break-chance");
        String preset = breakChance.choice("preset", "none", List.of("none", "legacy", "custom"));
        double perCharge = breakChance.decimal("per-charge", 0.0D, 0.0D, 1.0D);
        double finalCharge = breakChance.decimal("final-charge", 0.0D, 0.0D, 1.0D);
        if (!preset.equals("custom")) {
            if (perCharge != 0.0D) {
                breakChance.error("per-charge", "is only used with preset: custom (preset is " + preset + ")");
            }
            if (finalCharge != 0.0D) {
                breakChance.error("final-charge", "is only used with preset: custom (preset is " + preset + ")");
            }
            perCharge = preset.equals("legacy") ? DisarmerRules.LEGACY.perChargeBreakChance() : 0.0D;
            finalCharge = preset.equals("legacy") ? DisarmerRules.LEGACY.finalChargeBreakChance() : 0.0D;
        }
        return new DisarmerRules(max, rechargeSeconds * 1000L, perCharge, finalCharge);
    }

    /**
     * {@code appearance.charge-variants}: named sets mapping a charge count ({@code 0..max}) or
     * {@code broken} to a provider item. An item may appear in one set only.
     */
    public static List<Map<Integer, ProviderKey>> variants(ConfigReader appearance, int max) {
        List<Map<Integer, ProviderKey>> sets = new ArrayList<>();
        if (!appearance.has("charge-variants")) {
            return sets;
        }
        ConfigReader root = appearance.section("charge-variants");
        Set<ProviderKey> seen = new LinkedHashSet<>();
        for (String setName : root.keys()) {
            ConfigReader set = root.section(setName);
            Map<Integer, ProviderKey> parsed = new LinkedHashMap<>();
            for (String countKey : set.keys()) {
                Integer count = count(countKey, max);
                String raw = set.string(countKey, null);
                if (count == null) {
                    set.error(countKey, "must be a charge count from 0 to " + max + ", or broken");
                    continue;
                }
                if (raw == null) {
                    continue;
                }
                ProviderKey key;
                try {
                    key = ProviderKey.parse(raw.trim());
                } catch (IllegalArgumentException e) {
                    set.error(countKey, "invalid item reference '" + raw + "' (" + e.getMessage() + ")");
                    continue;
                }
                if (key.isVanilla() || key.isAllium()) {
                    set.error(countKey, "'" + raw + "' must be a provider item (nexo:, oraxen: or craftengine:);"
                            + " built-in tools show their charges by model instead");
                } else if (seen.contains(key) && !parsed.containsValue(key)) {
                    set.error(countKey, key + " is already in another variant set");
                } else {
                    parsed.put(count, key);
                }
            }
            seen.addAll(parsed.values());
            if (!parsed.isEmpty()) {
                sets.add(parsed);
            }
        }
        return sets;
    }

    private static Integer count(String raw, int max) {
        String trimmed = raw.trim();
        if ("broken".equals(trimmed.toLowerCase(Locale.ROOT))) {
            return DisarmParameters.BROKEN_VARIANT;
        }
        try {
            int count = Integer.parseInt(trimmed);
            return count >= 0 && count <= max ? count : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
