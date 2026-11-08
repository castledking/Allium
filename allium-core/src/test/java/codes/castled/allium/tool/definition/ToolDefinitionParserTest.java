package codes.castled.allium.tool.definition;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.disarmer.DisarmBehaviorType;
import codes.castled.allium.tool.disarmer.DisarmDropRule;
import codes.castled.allium.tool.disarmer.DisarmParameters;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolDefinitionParserTest {

    private static final String MOB_DISARMER_ID = "allium:mob_disarmer";

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

    private Map<String, Object> validDisarmerDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schema", 1);
        doc.put("id", MOB_DISARMER_ID);
        doc.put("name", "Mob Disarmer");
        doc.put("behavior", DisarmBehaviorType.ID);
        doc.put("enabled", true);
        doc.put("appearance", Map.of("default", "allium:mob_disarmer"));
        doc.put("charges", Map.of("max", 3, "recharge-seconds", 120, "break-chance", Map.of("preset", "none")));
        doc.put("targets", Map.of("radius", 6.0, "excluded-mobs", List.of("ender_dragon", "wither"), "exclude-npcs", true));
        doc.put("disarm", Map.of("drop-rule", "slot-chance", "pickup-block-seconds", 120));
        doc.put("timing", Map.of("click-guard-millis", 200, "status-cooldown-millis", 1000));
        doc.put("sounds", Map.of("disarm", Map.of("sound", "minecraft:item.shield.break", "volume", 1.0, "pitch", 1.2)));
        doc.put("permissions", Map.of("use", "allium.mobdisarmer.use", "bypass", "allium.mobdisarmer.bypass"));
        doc.put("messages", Map.of());
        return doc;
    }

    @Test
    void validDefinitionParsesSuccessfully() {
        ToolDefinitionParser.Result result = ToolDefinitionParser.parse("tools/mob_disarmer.yml", validDisarmerDoc(), behaviors, env);
        assertTrue(result.valid());
        ToolDefinition def = result.definition();
        assertNotNull(def);
        assertEquals(MOB_DISARMER_ID, def.id());
        assertEquals("mob_disarmer", def.localId());
        assertEquals(DisarmBehaviorType.ID, def.behavior());
        assertTrue(def.enabled());
        assertEquals(ProviderKey.allium("mob_disarmer"), def.appearance());
        DisarmParameters params = def.parameters(DisarmParameters.class);
        assertEquals(3, params.rules().maxCharges());
        assertEquals(6.0D, params.radius());
        assertEquals(DisarmDropRule.SLOT_CHANCE, params.dropRule());
    }

    @Test
    void invalidDefinitionsRejectedWholeWithPathSpecificDiagnostics() {
        // Unknown schema
        Map<String, Object> doc = validDisarmerDoc();
        doc.put("schema", 99);
        ToolDefinitionParser.Result result = ToolDefinitionParser.parse("tools/mob_disarmer.yml", doc, behaviors, env);
        assertFalse(result.valid());
        assertNull(result.definition());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("schema: must be 1")));

        // Unknown behavior
        doc = validDisarmerDoc();
        doc.put("behavior", "allium:nonexistent");
        result = ToolDefinitionParser.parse("tools/mob_disarmer.yml", doc, behaviors, env);
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("behavior: unknown behavior 'allium:nonexistent'")));

        // Unknown top-level key rejected without half-loading
        doc = validDisarmerDoc();
        doc.put("unknown_setting", true);
        result = ToolDefinitionParser.parse("tools/mob_disarmer.yml", doc, behaviors, env);
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("unknown_setting: unknown setting")));

        // Invalid entity type in targets.excluded-mobs
        doc = validDisarmerDoc();
        doc.put("targets", Map.of("radius", 6.0, "excluded-mobs", List.of("not_a_real_mob")));
        result = ToolDefinitionParser.parse("tools/mob_disarmer.yml", doc, behaviors, env);
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("targets.excluded-mobs[0]: 'not_a_real_mob' is not an entity type")));

        // Vanilla item rejected as appearance
        doc = validDisarmerDoc();
        doc.put("appearance", Map.of("default", "minecraft:diamond_sword"));
        result = ToolDefinitionParser.parse("tools/mob_disarmer.yml", doc, behaviors, env);
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("appearance.default: 'minecraft:diamond_sword' is a vanilla item")));
    }
}
