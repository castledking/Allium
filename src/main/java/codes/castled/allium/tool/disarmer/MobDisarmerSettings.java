package codes.castled.allium.tool.disarmer;

import codes.castled.allium.item.ProviderKey;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validated snapshot of {@code tools.mob-disarmer}. Invalid values fall back to their defaults
 * and are reported in {@link #warnings()}; nothing here touches the world or items.
 *
 * @param chargeVariants appearance sets; a disarmer whose appearance is in a set is shown as
 *                       that set's entry for its charge count. Key {@link #BROKEN_VARIANT} is
 *                       used for broken tools.
 */
public record MobDisarmerSettings(
        boolean enabled,
        ProviderKey appearance,
        List<ProviderKey> aliases,
        List<Map<Integer, ProviderKey>> chargeVariants,
        double radius,
        DisarmerRules rules,
        DisarmTargetPolicy targetPolicy,
        long pickupBlockMillis,
        long clickGuardMillis,
        long statusCooldownMillis,
        String sound,
        List<String> warnings
) {
    public static final ProviderKey DEFAULT_APPEARANCE = ProviderKey.allium("mob_disarmer");
    public static final int BROKEN_VARIANT = -1;
    private static final String PATH = "tools.mob-disarmer";

    public MobDisarmerSettings {
        aliases = List.copyOf(aliases);
        List<Map<Integer, ProviderKey>> sets = new ArrayList<>();
        for (Map<Integer, ProviderKey> set : chargeVariants) {
            sets.add(Collections.unmodifiableMap(new LinkedHashMap<>(set)));
        }
        chargeVariants = List.copyOf(sets);
        warnings = List.copyOf(warnings);
    }

    public static MobDisarmerSettings defaults() {
        return parse(null);
    }

    public static MobDisarmerSettings parse(@Nullable ConfigurationSection section) {
        List<String> warnings = new ArrayList<>();
        boolean enabled = section == null || section.getBoolean("enabled", true);

        ProviderKey appearance = parseAppearance(section == null ? null : section.getString("appearance"), "appearance", warnings);
        if (appearance == null) {
            appearance = DEFAULT_APPEARANCE;
        }

        List<ProviderKey> aliases = new ArrayList<>();
        if (section != null) {
            for (String raw : section.getStringList("aliases")) {
                ProviderKey alias = parseAppearance(raw, "aliases", warnings);
                if (alias != null && !alias.equals(appearance) && !aliases.contains(alias)) {
                    aliases.add(alias);
                }
            }
        }

        int maxCharges = section == null ? 3 : section.getInt("max-charges", 3);
        if (maxCharges < 1) {
            warnings.add(PATH + ".max-charges must be at least 1; using 3");
            maxCharges = 3;
        }

        List<Map<Integer, ProviderKey>> variants = new ArrayList<>();
        Set<ProviderKey> seen = new LinkedHashSet<>();
        ConfigurationSection variantRoot = section == null ? null : section.getConfigurationSection("charge-variants");
        if (variantRoot != null) {
            for (String setName : variantRoot.getKeys(false)) {
                ConfigurationSection set = variantRoot.getConfigurationSection(setName);
                if (set == null) {
                    warnings.add(PATH + ".charge-variants." + setName + " must map charge counts to item references; ignored");
                    continue;
                }
                Map<Integer, ProviderKey> parsed = new LinkedHashMap<>();
                boolean valid = true;
                for (String countKey : set.getKeys(false)) {
                    Integer count = parseVariantCount(countKey, maxCharges);
                    ProviderKey key = parseAppearance(set.getString(countKey), "charge-variants." + setName + "." + countKey, warnings);
                    if (count == null) {
                        warnings.add(PATH + ".charge-variants." + setName + "." + countKey
                                + " must be 0.." + maxCharges + " or 'broken'; set ignored");
                        valid = false;
                    } else if (key == null) {
                        valid = false;
                    } else if (seen.contains(key) && !parsed.containsValue(key)) {
                        warnings.add(PATH + ".charge-variants." + setName + " reuses " + key
                                + " from another set; set ignored");
                        valid = false;
                    } else {
                        parsed.put(count, key);
                    }
                }
                if (valid && !parsed.isEmpty()) {
                    seen.addAll(parsed.values());
                    variants.add(parsed);
                }
            }
        }

        double radius = section == null ? 6.0D : section.getDouble("radius", 6.0D);
        if (!Double.isFinite(radius) || radius < 0.0D) {
            warnings.add(PATH + ".radius must be 0 or more; using 6");
            radius = 6.0D;
        }

        long rechargeSeconds = section == null ? 120L : section.getLong("recharge-seconds", 120L);
        if (rechargeSeconds < 0L || rechargeSeconds > 31_536_000L) {
            warnings.add(PATH + ".recharge-seconds must be between 0 and 31536000; using 120");
            rechargeSeconds = 120L;
        }

        double perCharge = 0.0D;
        double finalCharge = 0.0D;
        String preset = section == null ? "none" : section.getString("break-chance.preset", "none");
        switch (preset == null ? "none" : preset.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> { }
            case "legacy" -> {
                perCharge = DisarmerRules.LEGACY.perChargeBreakChance();
                finalCharge = DisarmerRules.LEGACY.finalChargeBreakChance();
            }
            case "custom" -> {
                perCharge = probability(section.getDouble("break-chance.per-charge", 0.0D), "break-chance.per-charge", warnings);
                finalCharge = probability(section.getDouble("break-chance.final-charge", 0.0D), "break-chance.final-charge", warnings);
            }
            default -> warnings.add(PATH + ".break-chance.preset must be none, legacy or custom; using none");
        }
        DisarmerRules rules = new DisarmerRules(maxCharges, rechargeSeconds * 1000L, perCharge, finalCharge);

        List<String> excluded = section == null || !section.contains("excluded-mobs")
                ? List.copyOf(DisarmTargetPolicy.DEFAULT_EXCLUDED_TYPES)
                : section.getStringList("excluded-mobs");
        boolean excludeNpcs = section == null || section.getBoolean("exclude-npcs", true);

        long pickupBlockMillis = nonNegativeSeconds(section, "pickup-block-seconds", 120L, warnings) * 1000L;
        long clickGuardMillis = nonNegative(section, "click-guard-millis", 200L, warnings);
        long statusCooldownMillis = nonNegative(section, "status-cooldown-millis", 1000L, warnings);
        String sound = section == null ? "minecraft:item.shield.break" : section.getString("sound", "minecraft:item.shield.break");

        return new MobDisarmerSettings(enabled, appearance, aliases, variants, radius, rules,
                new DisarmTargetPolicy(excluded, excludeNpcs), pickupBlockMillis, clickGuardMillis,
                statusCooldownMillis, sound == null ? "" : sound.trim(), warnings);
    }

    /** Every non-vanilla appearance that should resolve to the Mob Disarmer behavior. */
    public Set<ProviderKey> boundAppearances() {
        Set<ProviderKey> keys = new LinkedHashSet<>();
        keys.add(appearance);
        keys.addAll(aliases);
        for (Map<Integer, ProviderKey> set : chargeVariants) {
            keys.addAll(set.values());
        }
        return keys;
    }

    /**
     * The appearance a disarmer currently shown as {@code current} should have for this state,
     * or empty when it is not in a variant set or the set has no entry for the state.
     */
    public Optional<ProviderKey> variantFor(ProviderKey current, DisarmerCharges state) {
        int slot = state.broken() ? BROKEN_VARIANT : state.charges();
        for (Map<Integer, ProviderKey> set : chargeVariants) {
            if (set.containsValue(current)) {
                ProviderKey target = set.get(slot);
                if (target == null && state.broken()) {
                    target = set.get(0);
                }
                return Optional.ofNullable(target);
            }
        }
        return Optional.empty();
    }

    @Nullable
    private static ProviderKey parseAppearance(@Nullable String raw, String field, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            ProviderKey key = ProviderKey.parse(raw.trim());
            if (key.isVanilla()) {
                warnings.add(PATH + "." + field + ": '" + raw + "' is a vanilla item; every such item would become a "
                        + "Mob Disarmer, so it is ignored. Use allium:, nexo:, oraxen: or craftengine:.");
                return null;
            }
            return key;
        } catch (IllegalArgumentException e) {
            warnings.add(PATH + "." + field + ": invalid item reference '" + raw + "' (" + e.getMessage() + "); ignored");
            return null;
        }
    }

    @Nullable
    private static Integer parseVariantCount(String raw, int maxCharges) {
        if ("broken".equalsIgnoreCase(raw.trim())) {
            return BROKEN_VARIANT;
        }
        try {
            int count = Integer.parseInt(raw.trim());
            return count >= 0 && count <= maxCharges ? count : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double probability(double value, String field, List<String> warnings) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            warnings.add(PATH + "." + field + " must be between 0 and 1; using 0");
            return 0.0D;
        }
        return value;
    }

    private static long nonNegativeSeconds(@Nullable ConfigurationSection section, String key, long fallback, List<String> warnings) {
        long value = nonNegative(section, key, fallback, warnings);
        if (value > 31_536_000L) {
            warnings.add(PATH + "." + key + " must be at most 31536000; using " + fallback);
            return fallback;
        }
        return value;
    }

    private static long nonNegative(@Nullable ConfigurationSection section, String key, long fallback, List<String> warnings) {
        long value = section == null ? fallback : section.getLong(key, fallback);
        if (value < 0L) {
            warnings.add(PATH + "." + key + " must be 0 or more; using " + fallback);
            return fallback;
        }
        return value;
    }
}
