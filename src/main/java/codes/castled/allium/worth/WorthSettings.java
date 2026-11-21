package codes.castled.allium.worth;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@code worth.yml}: item prices for {@code /worth} and {@code /sell}, and how selling behaves.
 *
 * <p>A price key is an item name ({@code diamond_pickaxe}), Essentials' form of it
 * ({@code diamondpickaxe}), or an Allium item alias. As in Essentials, a section such as
 * {@code {'0': 2.0, '*': 2.5}} uses its only entry, else {@code '*'}. A negative price means the item
 * cannot be sold; 0 sells it for nothing.
 */
public record WorthSettings(Map<String, BigDecimal> prices, Map<String, BigDecimal> multipliers, boolean allowNamedItems,
                            Set<String> tradeInStacks, int confirmBulkSeconds, boolean allowCreative) {

    public static final String MULTIPLIER_PERMISSION = "allium.sell.multiplier.";
    /** The highest price of one item: with the highest multiplier a full inventory stays within the money bound. */
    public static final BigDecimal MAX_PRICE = BigDecimal.valueOf(1_000_000_000L);

    public WorthSettings {
        prices = Map.copyOf(prices);
        multipliers = Map.copyOf(multipliers);
        tradeInStacks = Set.copyOf(tradeInStacks);
        if (confirmBulkSeconds < 0 || confirmBulkSeconds > 300) throw new IllegalArgumentException("confirm-bulk-seconds must be 0 to 300");
        for (var multiplier : multipliers.entrySet()) {
            if (multiplier.getValue().signum() < 0 || multiplier.getValue().compareTo(BigDecimal.valueOf(1000)) > 0) {
                throw new IllegalArgumentException("multiplier " + multiplier.getKey() + " must be 0 to 1000");
            }
        }
    }

    /** {@code DIAMOND_PICKAXE}, {@code diamond_pickaxe}, {@code minecraft:diamond_pickaxe} and {@code diamondpickaxe} alike. */
    public static String key(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        return key.replace("_", "");
    }

    public static String key(Material material) {
        return key(material.name());
    }

    /**
     * @param alias an Allium item alias's material, or null; used for keys that are not item names
     * @param warn  told about entries that name no item or have unusable prices
     */
    public static WorthSettings read(ConfigurationSection c, Function<String, Material> alias, Consumer<String> warn) {
        Map<String, Material> materials = new LinkedHashMap<>();
        for (Material material : Material.values()) {
            if (!material.name().startsWith("LEGACY_")) materials.put(key(material), material);
        }
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        ConfigurationSection worth = c.getConfigurationSection("worth");
        if (worth != null) {
            for (String name : worth.getKeys(false)) {
                Material material = materials.get(key(name));
                if (material == null) material = alias.apply(name);
                if (material == null) {
                    warn.accept("worth.yml names no item '" + name + "'; it is ignored");
                    continue;
                }
                Object raw = worth.get(name);
                if (raw instanceof ConfigurationSection section) {
                    Set<String> keys = section.getKeys(false);
                    raw = keys.size() == 1 ? section.get(keys.iterator().next()) : section.get("*");
                    if (raw == null) continue;
                }
                BigDecimal price = price(raw, name, warn);
                if (price != null) prices.put(key(material), price);
            }
        }
        Map<String, BigDecimal> multipliers = new LinkedHashMap<>();
        ConfigurationSection tiers = c.getConfigurationSection("multipliers");
        if (tiers != null) {
            for (String tier : tiers.getKeys(false)) {
                BigDecimal value = price(tiers.get(tier), "multipliers." + tier, warn);
                if (value != null) multipliers.put(tier.toLowerCase(Locale.ROOT), value);
            }
        }
        Set<String> stacks = new LinkedHashSet<>();
        for (String name : c.getStringList("trade-in-stacks")) stacks.add(key(name));
        return new WorthSettings(prices, multipliers, c.getBoolean("allow-named-items", false), stacks,
                c.getInt("confirm-bulk-seconds", 15), c.getBoolean("allow-creative", false));
    }

    @Nullable
    private static BigDecimal price(Object raw, String name, Consumer<String> warn) {
        try {
            BigDecimal value = new BigDecimal(String.valueOf(raw).trim());
            if (value.signum() < 0) return BigDecimal.ONE.negate();
            if (value.compareTo(MAX_PRICE) > 0) {
                warn.accept("worth.yml " + name + " " + raw + " is above the highest price, " + MAX_PRICE.toPlainString() + "; it is ignored");
                return null;
            }
            BigDecimal cents = value.setScale(2, RoundingMode.HALF_UP);
            if (cents.compareTo(value) != 0) warn.accept("worth.yml " + name + " " + raw + " is rounded to " + cents);
            return cents;
        } catch (NumberFormatException invalid) {
            warn.accept("worth.yml " + name + " is not a number ('" + raw + "'); it is ignored");
            return null;
        }
    }

    /** The price of one {@code material}, or empty when it cannot be sold. */
    public Optional<BigDecimal> price(Material material) {
        BigDecimal price = prices.get(key(material));
        return price == null || price.signum() < 0 ? Optional.empty() : Optional.of(price);
    }

    public boolean stacksOnly(Material material) {
        return tradeInStacks.contains(key(material));
    }

    /** The highest multiplier whose {@code allium.sell.multiplier.<name>} the seller holds, else {@code default} (1). */
    public BigDecimal multiplier(Predicate<String> hasPermission) {
        BigDecimal best = multipliers.getOrDefault("default", BigDecimal.ONE);
        for (var tier : multipliers.entrySet()) {
            if (!tier.getKey().equals("default") && hasPermission.test(MULTIPLIER_PERMISSION + tier.getKey())
                    && tier.getValue().compareTo(best) > 0) {
                best = tier.getValue();
            }
        }
        return best;
    }
}
