package codes.castled.allium.tool.disarmer;

import codes.castled.allium.item.ProviderKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MobDisarmerSettingsTest {

    @Test
    void shippedDefaultsMatchTheDocumentedBehavior() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new java.io.InputStreamReader(
                MobDisarmerSettingsTest.class.getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8));
        MobDisarmerSettings shipped = MobDisarmerSettings.parse(config.getConfigurationSection("tools.mob-disarmer"));
        assertEquals(List.of(), shipped.warnings());
        assertEquals(MobDisarmerSettings.defaults().rules(), shipped.rules());
        assertEquals(new DisarmerRules(3, 120_000L, 0.0D, 0.0D), shipped.rules());
        assertEquals(ProviderKey.allium("mob_disarmer"), shipped.appearance());
        assertEquals(6.0D, shipped.radius());
        assertEquals(DisarmTargetPolicy.DEFAULT_EXCLUDED_TYPES, shipped.targetPolicy().excludedTypes());
        assertEquals(120_000L, shipped.pickupBlockMillis());
        assertTrue(shipped.chargeVariants().isEmpty());
        // Pre-recode Allium rolled each slot's own chance; the shipped default keeps that.
        assertEquals(DisarmDropRule.SLOT_CHANCE, shipped.dropRule());
        assertEquals(DisarmDropRule.SLOT_CHANCE, MobDisarmerSettings.defaults().dropRule());
    }

    @Test
    void dropRuleAcceptsAlwaysAndFallsBackToSlotChanceWithAWarning() throws Exception {
        YamlConfiguration always = new YamlConfiguration();
        always.loadFromString("drop-rule: always\n");
        assertEquals(DisarmDropRule.ALWAYS, MobDisarmerSettings.parse(always).dropRule());
        assertEquals(List.of(), MobDisarmerSettings.parse(always).warnings());
        YamlConfiguration invalid = new YamlConfiguration();
        invalid.loadFromString("drop-rule: sometimes\n");
        MobDisarmerSettings parsed = MobDisarmerSettings.parse(invalid);
        assertEquals(DisarmDropRule.SLOT_CHANCE, parsed.dropRule());
        assertEquals(List.of("tools.mob-disarmer.drop-rule must be always or slot-chance; using slot-chance"), parsed.warnings());
    }

    @Test
    void invalidValuesFallBackWithWarningsAndVanillaAppearancesAreRefused() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                appearance: minecraft:golden_hoe
                aliases: [nexo:mob_disarmer, "bad ref::", minecraft:stick, nexo:mob_disarmer]
                max-charges: 0
                radius: -2
                recharge-seconds: -1
                break-chance: { preset: custom, per-charge: 2.0, final-charge: 0.25 }
                click-guard-millis: -5
                """);
        MobDisarmerSettings settings = MobDisarmerSettings.parse(config);
        assertEquals(ProviderKey.allium("mob_disarmer"), settings.appearance());
        assertEquals(List.of(ProviderKey.of("nexo", "mob_disarmer")), settings.aliases());
        assertEquals(new DisarmerRules(3, 120_000L, 0.0D, 0.25D), settings.rules());
        assertEquals(6.0D, settings.radius());
        assertEquals(200L, settings.clickGuardMillis());
        assertTrue(settings.warnings().size() >= 7, settings.warnings().toString());
    }

    @Test
    void chargeVariantSetsSwapOnlyWithinTheirSetAndLegacyPresetMatchesOldBreakChances() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                appearance: nexo:mob_disarmer
                break-chance: { preset: legacy }
                charge-variants:
                  sf:
                    '3': nexo:mob_disarmer
                    '2': nexo:mob_disarmer_2-3
                    '1': nexo:mob_disarmer_1-3
                    '0': nexo:mob_disarmer_0-3
                  bad:
                    '7': oraxen:x
                  overlap:
                    '1': nexo:mob_disarmer_1-3
                """);
        MobDisarmerSettings settings = MobDisarmerSettings.parse(config);
        assertEquals(DisarmerRules.LEGACY, settings.rules());
        assertEquals(1, settings.chargeVariants().size());
        assertEquals(2, settings.warnings().size(), settings.warnings().toString());
        ProviderKey full = ProviderKey.of("nexo", "mob_disarmer");
        assertEquals(Optional.of(ProviderKey.of("nexo", "mob_disarmer_2-3")),
                settings.variantFor(full, new DisarmerCharges(2, 0L, false, 0L)));
        assertEquals(Optional.of(ProviderKey.of("nexo", "mob_disarmer_0-3")),
                settings.variantFor(ProviderKey.of("nexo", "mob_disarmer_1-3"), new DisarmerCharges(0, 0L, true, 0L)));
        assertEquals(Optional.empty(), settings.variantFor(ProviderKey.allium("mob_disarmer"), new DisarmerCharges(2, 0L, false, 0L)));
        assertTrue(settings.boundAppearances().contains(ProviderKey.of("nexo", "mob_disarmer_0-3")));
    }
}
