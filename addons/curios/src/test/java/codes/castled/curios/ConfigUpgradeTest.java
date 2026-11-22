package codes.castled.curios;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ConfigUpgradeTest {

    private static YamlConfiguration bundled() throws Exception {
        try (var in = ConfigUpgradeTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** A file as builds before version 2 wrote it: no version, card-menu or resource-pack, textures off. */
    private static YamlConfiguration versionOne() throws Exception {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(new java.io.StringReader(bundled().saveToString()));
        old.set("config-version", null);
        old.set("card-menu", null);
        old.set("resource-pack", null);
        old.set("textured-menu", false);
        old.set("frames.red", "custom.permission");
        old.setDefaults(bundled());
        return old;
    }

    @Test
    void bundledConfigIsCurrent() throws Exception {
        YamlConfiguration config = bundled();
        config.setDefaults(bundled());
        assertEquals(ConfigUpgrade.VERSION, config.getInt("config-version"));
        assertFalse(ConfigUpgrade.apply(config));
    }

    @Test
    void versionOneGainsItsMissingSectionsAndTexturesWithoutLosingEdits() throws Exception {
        YamlConfiguration config = versionOne();
        // Before the upgrade Bukkit hands CardRules an empty section, not the jar's mob colours.
        assertTrue(config.getConfigurationSection("card-menu").getKeys(false).isEmpty());

        assertTrue(ConfigUpgrade.apply(config));

        YamlConfiguration saved = YamlConfiguration.loadConfiguration(new java.io.StringReader(config.saveToString()));
        assertEquals(ConfigUpgrade.VERSION, saved.getInt("config-version"));
        assertTrue(saved.getBoolean("textured-menu"));
        assertEquals("allium", saved.getString("resource-pack.delivery"));
        assertEquals("gold", saved.getString("card-menu.mob-colours.fox"));
        assertEquals(8, saved.getConfigurationSection("card-menu.quality-bands").getKeys(false).size());
        assertEquals("custom.permission", saved.getString("frames.red"));
        assertEquals("gold", new CardRules(saved).mobColour("fox"));
        assertFalse(ConfigUpgrade.apply(saved));
    }

    @Test
    void aCurrentConfigKeepsTexturesOff() throws Exception {
        YamlConfiguration config = bundled();
        config.set("textured-menu", false);
        config.setDefaults(bundled());
        assertFalse(ConfigUpgrade.apply(config));
        assertFalse(config.getBoolean("textured-menu"));
    }

    @Test
    void cardTitlesKeepLegacyColoursEvenWithoutAConfiguredSection() {
        CardRules rules = new CardRules(new YamlConfiguration());
        assertEquals("gold", rules.mobColour("fox"));
        assertEquals("dark_gray", rules.mobColour("wither_skeleton"));
        assertEquals(36, CardRules.LEGACY_MOB_COLOURS.size());
        assertEquals("white", rules.mobColour("warden"));
    }
}
