package codes.castled.allium.tool;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.disarmer.DisarmBehaviorType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolDefinitionLoaderTest {

    @TempDir
    File dataFolder;

    private List<String> infoLogs;
    private List<String> warnLogs;

    private final ToolEnvironment env = new ToolEnvironment() {
        private final Set<String> entities = Set.of(
                "minecraft:ender_dragon",
                "minecraft:wither",
                "minecraft:elder_guardian",
                "minecraft:warden",
                "minecraft:zombie"
        );

        @Override
        public boolean isItemMaterial(String material) {
            return "golden_hoe".equalsIgnoreCase(material) || "stick".equalsIgnoreCase(material);
        }

        @Override
        public boolean isEntityType(String entityType) {
            return entities.contains(entityType);
        }

        @Override
        public boolean hasLangKey(String key) {
            return true;
        }

        @Override
        public boolean isCoreItem(String localId) {
            return "mob_disarmer".equals(localId);
        }

        @Override
        public Optional<String> coreItemBehavior(String localId) {
            return "mob_disarmer".equals(localId) ? Optional.of(DisarmBehaviorType.ID) : Optional.empty();
        }
    };

    private final Map<String, ToolBehaviorType<?>> behaviors = Map.of(DisarmBehaviorType.ID, new DisarmBehaviorType());

    private static final String BUNDLED_YAML = """
            schema: 1
            id: allium:mob_disarmer
            name: Mob Disarmer
            behavior: allium:disarm
            enabled: true
            appearance:
              default: allium:mob_disarmer
            charges:
              max: 3
              recharge-seconds: 120
              break-chance:
                preset: none
            targets:
              radius: 6.0
              excluded-mobs: [ender_dragon, wither]
              exclude-npcs: true
            disarm:
              drop-rule: slot-chance
              pickup-block-seconds: 120
            timing:
              click-guard-millis: 200
              status-cooldown-millis: 1000
            sounds:
              disarm:
                sound: minecraft:item.shield.break
                volume: 1.0
                pitch: 1.2
            permissions:
              use: allium.mobdisarmer.use
              bypass: allium.mobdisarmer.bypass
            messages: {}
            """;

    @BeforeEach
    void setUp() {
        infoLogs = new ArrayList<>();
        warnLogs = new ArrayList<>();
    }

    private ToolDefinitionLoader newLoader() {
        return new ToolDefinitionLoader(
                dataFolder,
                () -> new ByteArrayInputStream(BUNDLED_YAML.getBytes(StandardCharsets.UTF_8)),
                infoLogs::add,
                warnLogs::add
        );
    }

    @Test
    void installsDefaultMobDisarmerWhenToolsFolderMissing() throws IOException {
        ToolDefinitionLoader loader = newLoader();
        loader.install(null);

        File toolsDir = new File(dataFolder, "tools");
        assertTrue(toolsDir.isDirectory());
        File mobDisarmerFile = new File(toolsDir, "mob_disarmer.yml");
        assertTrue(mobDisarmerFile.isFile());
        assertTrue(infoLogs.stream().anyMatch(l -> l.contains("Created tools/mob_disarmer.yml")));

        ToolDefinitionLoader.Loaded loaded = loader.load(behaviors, env, key -> Optional.empty());
        assertEquals(1, loaded.definitions().size());
        assertEquals("allium:mob_disarmer", loaded.definitions().get(0).id());
        assertTrue(loaded.rejected().isEmpty());
    }

    @Test
    void rejectsConflictingToolDefinitionsAndInvalidFilesWithoutDroppingValidOnes() throws IOException {
        ToolDefinitionLoader loader = newLoader();
        loader.install(null);

        File toolsDir = new File(dataFolder, "tools");

        // Valid second tool
        String secondYaml = """
                schema: 1
                id: allium:wand_disarmer
                name: Disarm Wand
                behavior: allium:disarm
                enabled: true
                item:
                  material: stick
                  name: Disarm Wand
                appearance:
                  default: allium:wand_disarmer
                charges:
                  max: 5
                  recharge-seconds: 60
                  break-chance:
                    preset: none
                targets:
                  radius: 10.0
                  excluded-mobs: []
                  exclude-npcs: false
                disarm:
                  drop-rule: always
                  pickup-block-seconds: 60
                timing:
                  click-guard-millis: 100
                  status-cooldown-millis: 500
                sounds:
                  disarm:
                    sound: minecraft:item.shield.break
                    volume: 1.0
                    pitch: 1.0
                permissions:
                  use: allium.wanddisarmer.use
                  bypass: allium.wanddisarmer.bypass
                messages: {}
                """;
        Files.writeString(new File(toolsDir, "wand_disarmer.yml").toPath(), secondYaml);

        // Invalid third tool (invalid schema)
        String badYaml = """
                schema: 99
                id: allium:bad_tool
                name: Bad Tool
                behavior: allium:disarm
                enabled: true
                """;
        Files.writeString(new File(toolsDir, "bad_tool.yml").toPath(), badYaml);

        ToolDefinitionLoader.Loaded loaded = loader.load(behaviors, env, key -> Optional.empty());

        // 2 valid (mob_disarmer, wand_disarmer)
        assertEquals(2, loaded.definitions().size());
        assertEquals(List.of("allium:mob_disarmer", "allium:wand_disarmer"),
                loaded.definitions().stream().map(ToolDefinition::id).toList());

        // 1 rejected (bad_tool)
        assertEquals(1, loaded.rejected().size());
        assertEquals("tools/bad_tool.yml", loaded.rejected().get(0).file());
    }
}
