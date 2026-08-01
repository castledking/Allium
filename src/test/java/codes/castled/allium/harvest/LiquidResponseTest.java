package codes.castled.allium.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.castled.allium.harvest.config.HarvestConfig;
import codes.castled.allium.harvest.crop.LiquidResponse;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LiquidResponseTest {

    private static YamlConfiguration parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    @Test
    void parsingIsCaseAndWhitespaceInsensitive() {
        assertEquals(LiquidResponse.DROP, LiquidResponse.parse("drop").orElseThrow());
        assertEquals(LiquidResponse.BURN, LiquidResponse.parse("  BURN  ").orElseThrow());
        assertEquals(LiquidResponse.PROTECT, LiquidResponse.parse("Protect").orElseThrow());
        assertTrue(LiquidResponse.parse("melt").isEmpty());
        assertTrue(LiquidResponse.parse("").isEmpty());
        assertTrue(LiquidResponse.parse(null).isEmpty());
    }

    @Test
    void onlyProtectLeavesTheCropStanding() {
        assertTrue(LiquidResponse.PROTECT.survives());
        assertFalse(LiquidResponse.DROP.survives());
        assertFalse(LiquidResponse.BURN.survives());
        assertFalse(LiquidResponse.DESTROY.survives());
    }

    /** An absent block must give the vanilla split, not "liquids do nothing". */
    @Test
    void missingSectionDefaultsToVanillaBehaviour() {
        HarvestConfig.Liquids liquids = HarvestConfig.from(new YamlConfiguration()).liquids();
        assertTrue(liquids.enabled());
        assertEquals(LiquidResponse.DROP, liquids.water());
        assertEquals(LiquidResponse.BURN, liquids.lava());
    }

    @Test
    void readsConfiguredResponses() throws InvalidConfigurationException {
        HarvestConfig.Liquids liquids = HarvestConfig.from(parse("""
            liquids:
              enabled: true
              water: PROTECT
              lava: DESTROY
            """)).liquids();
        assertEquals(LiquidResponse.PROTECT, liquids.water());
        assertEquals(LiquidResponse.DESTROY, liquids.lava());
    }

    /**
     * A typo costs you the one setting, not the module: a crop left standing
     * inside a fluid block is a worse outcome than the wrong response.
     */
    @Test
    void unrecognisedResponseFallsBackPerFluid() throws InvalidConfigurationException {
        HarvestConfig.Liquids liquids = HarvestConfig.from(parse("""
            liquids:
              water: DORP
              lava: DESTROY
            """)).liquids();
        assertEquals(LiquidResponse.DROP, liquids.water());
        assertEquals(LiquidResponse.DESTROY, liquids.lava());
        assertTrue(liquids.enabled());
    }

    @Test
    void canBeTurnedOffEntirely() throws InvalidConfigurationException {
        assertFalse(HarvestConfig.from(parse("""
            liquids:
              enabled: false
            """)).liquids().enabled());
    }
}
