package codes.castled.allium.explosion;

import codes.castled.allium.explosion.ExplosionSettings.Explosive;
import codes.castled.allium.explosion.ExplosionSettings.Source;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionSettingsTest {

    private static ExplosionSettings read(String yaml, List<String> warnings) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return ExplosionSettings.read(config, warnings::add);
    }

    @Test
    void theBundledFileReadsWithoutWarningsAndRegeneratesCreepersGhastsAndTheCommands() throws Exception {
        YamlConfiguration bundled;
        try (var in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("explosions.yml"))) {
            bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> warnings = new ArrayList<>();
        ExplosionSettings settings = ExplosionSettings.read(bundled, warnings::add);
        assertEquals(List.of(), warnings);
        assertTrue(settings.regenerates(Source.CREEPER));
        assertTrue(settings.regenerates(Source.GHAST_FIREBALL));
        for (Source off : List.of(Source.WITHER, Source.TNT, Source.END_CRYSTAL, Source.BLOCK, Source.OTHER)) {
            assertFalse(settings.regenerates(off), off.key());
        }
        assertTrue(settings.regenerates(Explosive.ANTIOCH));
        assertTrue(settings.regenerates(Explosive.FIREBALL));
        assertTrue(settings.regenerates(Explosive.EXPLODE));
        assertEquals(100L, settings.delayTicks());
        assertEquals(2.0D, settings.blocksPerTick());
        assertTrue(settings.skipClaims());
        assertTrue(settings.protectHanging());
        assertEquals(8.0D, settings.fireballMaxSpeed());
        assertEquals(1, settings.explodeMinPower());
        assertEquals(10, settings.explodeMaxPower());
        assertEquals(2, settings.explodeDefaultPower());
        assertTrue(settings.antiochAnnounce());
    }

    @Test
    void antiochCanBeKeptQuiet() throws Exception {
        assertFalse(read("antioch:\n  announce: false\n", new ArrayList<>()).antiochAnnounce());
    }

    @Test
    void anEmptyFileUsesTheDefaults() throws Exception {
        List<String> warnings = new ArrayList<>();
        ExplosionSettings settings = read("", warnings);
        assertEquals(List.of(), warnings);
        assertTrue(settings.regenerates(Source.CREEPER));
        assertFalse(settings.regenerates(Source.TNT));
        assertTrue(settings.regenerates(Explosive.EXPLODE));
        assertTrue(settings.worldEnabled("world"));
        assertEquals(10_000, settings.maxBlocks());
    }

    @Test
    void commandsThatBreakNoBlocksNeverRegenerateAndHaveNoSwitch() throws Exception {
        List<String> warnings = new ArrayList<>();
        ExplosionSettings settings = read("""
                regeneration:
                  commands:
                    antioch: false
                    nuke: true
                """, warnings);
        assertFalse(settings.regenerates(Explosive.ANTIOCH));
        assertTrue(settings.regenerates(Explosive.FIREBALL));
        assertFalse(settings.regenerates(Explosive.NUKE));
        assertFalse(settings.regenerates(Explosive.BEEZOOKA));
        assertFalse(settings.regenerates(Explosive.KITTYCANNON));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("commands.nuke"), warnings.get(0));
    }

    @Test
    void worldsAreCaseInsensitiveAndBadValuesFallBackWithAWarning() throws Exception {
        List<String> warnings = new ArrayList<>();
        ExplosionSettings settings = read("""
                regeneration:
                  sources:
                    creeper: false
                    tnt: true
                    lightning: true
                  disabled-worlds: [Creative_Plots, ' ']
                  delay-seconds: -2
                  blocks-per-second: 0
                  ignored-blocks: [minecraft:tnt, spawner, diamond, notablock]
                  max-blocks: 0
                fireball:
                  max-speed: 1000
                explode:
                  min-power: 5
                  max-power: 3
                  default-power: 50
                """, warnings);
        assertFalse(settings.regenerates(Source.CREEPER));
        assertTrue(settings.regenerates(Source.TNT));
        assertFalse(settings.worldEnabled("creative_plots"));
        assertFalse(settings.worldEnabled("CREATIVE_PLOTS"));
        assertTrue(settings.worldEnabled("world"));
        assertEquals(100L, settings.delayTicks());
        assertEquals(40.0D, settings.blocksPerSecond());
        assertEquals(Set.of(Material.TNT, Material.SPAWNER), settings.ignored());
        assertEquals(10_000, settings.maxBlocks());
        assertEquals(8.0D, settings.fireballMaxSpeed());
        assertEquals(1, settings.explodeMinPower());
        assertEquals(10, settings.explodeMaxPower());
        assertEquals(2, settings.explodeDefaultPower());
        // lightning, delay, blocks-per-second, diamond (an item), notablock, max-blocks, max-speed, the powers, the default
        assertEquals(9, warnings.size(), String.join("\n", warnings));
    }

    @Test
    void aDefaultPowerOutsideTheRangeMovesInsideIt() throws Exception {
        List<String> warnings = new ArrayList<>();
        ExplosionSettings settings = read("""
                explode:
                  min-power: 20
                  max-power: 127
                  default-power: 2
                """, warnings);
        assertEquals(20, settings.explodeMinPower());
        assertEquals(127, settings.explodeMaxPower());
        assertEquals(20, settings.explodeDefaultPower());
        assertEquals(1, warnings.size());
    }

    @Test
    void witherSpawnWorldRuleIsIndependentOfWitherExplosionRegeneration() throws Exception {
        List<String> warnings = new ArrayList<>();
        ExplosionSettings defaults = read("", warnings);
        assertFalse(defaults.blocksWitherSpawn("world", org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_WITHER));
        assertFalse(defaults.blocksWitherSpawn("world_nether", org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NETHER_PORTAL));

        ExplosionSettings scoped = read("""
                regeneration:
                  sources:
                    wither: true
                wither-spawn:
                  block-all-worlds: false
                  blocked-worlds: [World, Spawn_Hub]
                  block-all-reasons: false
                """, warnings);
        assertTrue(scoped.regenerates(Source.WITHER), "explosion regen remains independent of spawn rule");
        assertTrue(scoped.blocksWitherSpawn("world", org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_WITHER));
        assertTrue(scoped.blocksWitherSpawn("SPAWN_HUB", org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NETHER_PORTAL));
        assertFalse(scoped.blocksWitherSpawn("world", org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM));
        assertFalse(scoped.blocksWitherSpawn("world_nether", org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_WITHER));

        ExplosionSettings globalAllReasons = read("""
                wither-spawn:
                  block-all-worlds: true
                  block-all-reasons: true
                """, warnings);
        assertTrue(globalAllReasons.blocksWitherSpawn("world_the_end", org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER_EGG));
        assertEquals(List.of(), warnings);
    }
}
