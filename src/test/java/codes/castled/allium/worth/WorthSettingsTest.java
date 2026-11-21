package codes.castled.allium.worth;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorthSettingsTest {

    private static WorthSettings read(String yaml, List<String> warnings) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return WorthSettings.read(config, name -> name.equals("dpick") ? Material.DIAMOND_PICKAXE : null, warnings::add);
    }

    @Test
    void essentialsKeysSectionsAndAliasesNameTheirItems() throws Exception {
        List<String> warnings = new ArrayList<>();
        WorthSettings settings = read("""
                worth:
                  diamondpickaxe: 650
                  minecraft:cobblestone: 0.5
                  OAK_LOG: {'0': 2.0, '*': 2.5}
                  white_wool: {'0': 3}
                  dpick: 700
                  stick: -1
                  dirt: 0
                  feather: 1.005
                  notanitem: 9
                  gold_ingot: lots
                """, warnings);
        // The alias key comes later and wins for the same item.
        assertEquals(new BigDecimal("700.00"), settings.price(Material.DIAMOND_PICKAXE).orElseThrow());
        assertEquals(new BigDecimal("0.50"), settings.price(Material.COBBLESTONE).orElseThrow());
        assertEquals(new BigDecimal("2.50"), settings.price(Material.OAK_LOG).orElseThrow());
        assertEquals(new BigDecimal("3.00"), settings.price(Material.WHITE_WOOL).orElseThrow());
        assertTrue(settings.price(Material.STICK).isEmpty());
        assertEquals(BigDecimal.ZERO.setScale(2), settings.price(Material.DIRT).orElseThrow());
        assertEquals(new BigDecimal("1.01"), settings.price(Material.FEATHER).orElseThrow());
        assertTrue(settings.price(Material.GOLD_INGOT).isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("notanitem")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("gold_ingot")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("rounded")), warnings.toString());
    }

    @Test
    void theHighestHeldMultiplierApplies() throws Exception {
        WorthSettings settings = read("""
                multipliers: {default: 1.0, double: 2.0, triple: 3.0}
                trade-in-stacks: [cobblestone]
                worth: {}
                """, new ArrayList<>());
        assertEquals(0, BigDecimal.ONE.compareTo(settings.multiplier(node -> false)));
        assertEquals(0, new BigDecimal("2").compareTo(settings.multiplier(node -> node.equals("allium.sell.multiplier.double"))));
        assertEquals(0, new BigDecimal("3").compareTo(settings.multiplier(node -> node.startsWith("allium.sell.multiplier."))));
        assertTrue(settings.stacksOnly(Material.COBBLESTONE));
        assertFalse(settings.stacksOnly(Material.DIRT));
        assertEquals(15, settings.confirmBulkSeconds());
    }

    @Test
    void pricesAboveTheLimitAreIgnored() throws Exception {
        List<String> warnings = new ArrayList<>();
        WorthSettings settings = read("worth: {diamond: 1000000000.01, emerald: 1000000000}\n", warnings);
        assertTrue(settings.price(Material.DIAMOND).isEmpty());
        assertEquals(0, WorthSettings.MAX_PRICE.compareTo(settings.price(Material.EMERALD).orElseThrow()));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void theBundledWorthYmlNamesOnlyItemsOnEveryVersion() throws Exception {
        YamlConfiguration bundled = new YamlConfiguration();
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/worth.yml")), StandardCharsets.UTF_8)) {
            bundled.load(reader);
        }
        List<String> warnings = new ArrayList<>();
        WorthSettings settings = WorthSettings.read(bundled, name -> null, warnings::add);
        // Items renamed after 1.20.1 (short_grass, turtle_scute) are absent from this API's names and warn.
        assertTrue(warnings.stream().allMatch(w -> w.contains("names no item")), warnings.toString());
        assertTrue(settings.prices().size() >= 180, "prices: " + settings.prices().size());
        assertEquals(new BigDecimal("650.00"), settings.price(Material.DIAMOND_PICKAXE).orElseThrow());
        assertEquals(Map.of("default", new BigDecimal("1.00"), "double", new BigDecimal("2.00"), "triple", new BigDecimal("3.00")),
                settings.multipliers());
        assertEquals(Set.of(), settings.tradeInStacks());
        assertFalse(settings.allowNamedItems());
        assertFalse(settings.allowCreative());
        for (String key : bundled.getConfigurationSection("worth").getKeys(false)) {
            assertEquals(key, key.toLowerCase(java.util.Locale.ROOT), key);
        }
    }
}
