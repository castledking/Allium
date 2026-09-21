package codes.castled.allium.spawnercraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerHeadConfigTest {

    private static Map<EntityType, SpawnerHeadConfig.MobHead> parse(String yaml) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        return SpawnerHeadConfig.parse(config.getConfigurationSection("heads"), null);
    }

    @Test
    void readsChancesAndComponents() {
        Map<EntityType, SpawnerHeadConfig.MobHead> heads = parse("""
                heads:
                  ENDERMAN:
                    enabled: true
                    chance: 0.002
                    looting-chance: 0.003
                    looting-chance-per-level: 0.0005
                    sound: "entity.enderman.scream"
                    texture: "abc"
                """);
        SpawnerHeadConfig.MobHead head = heads.get(EntityType.ENDERMAN);
        assertEquals(0.002, head.chance());
        assertEquals(0.003, head.lootingChance());
        assertEquals(0.0005, head.lootingPerLevel());
        assertEquals("entity.enderman.scream", head.sound());
    }

    @Test
    void disabledAndUnknownMobsAreSkipped() {
        Map<EntityType, SpawnerHeadConfig.MobHead> heads = parse("""
                heads:
                  PIG:
                    enabled: false
                    chance: 0.01
                    sound: "entity.pig.ambient"
                    texture: "abc"
                  NOT_A_MOB:
                    chance: 0.01
                    sound: "entity.nope.ambient"
                    texture: "abc"
                  COW:
                    chance: 0.01
                    sound: "entity.cow.ambient"
                    texture: "abc"
                """);
        assertFalse(heads.containsKey(EntityType.PIG));
        assertTrue(heads.containsKey(EntityType.COW));
        assertEquals(1, heads.size());
    }

    @Test
    void entriesWithoutSoundOrTextureAreSkipped() {
        assertTrue(parse("""
                heads:
                  COW:
                    chance: 0.01
                    texture: "abc"
                """).isEmpty());
    }

    @Test
    void soundMobIsTheEntityNameSegment() {
        assertEquals("magma_cube", SpawnerHeadConfig.soundMob("entity.magma_cube.squish"));
        assertEquals("cow", SpawnerHeadConfig.soundMob("minecraft:entity.cow.ambient"));
        assertEquals("", SpawnerHeadConfig.soundMob("block.note_block.harp"));
    }
}
