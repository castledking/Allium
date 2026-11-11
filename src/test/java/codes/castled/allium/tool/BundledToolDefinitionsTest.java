package codes.castled.allium.tool;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolDefinitionParser;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.definition.ToolSound;
import codes.castled.allium.tool.disarmer.DisarmBehaviorType;
import codes.castled.allium.tool.obliterator.ObliterateBehaviorType;
import codes.castled.allium.tool.obliterator.ObliterateParameters;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The definitions the jar ships parse cleanly and say what the docs say they do. */
class BundledToolDefinitionsTest {

    private static final Map<String, ToolBehaviorType<?>> BEHAVIORS = Map.of(
            DisarmBehaviorType.ID, new DisarmBehaviorType(),
            ObliterateBehaviorType.ID, new ObliterateBehaviorType());

    private static final ToolEnvironment ENV = new ToolEnvironment() {
        public boolean isItemMaterial(String material) { return true; }
        public boolean isEntityType(String entityType) { return entityType.startsWith("minecraft:"); }
        public boolean hasLangKey(String key) { return true; }
        public boolean isCoreItem(String localId) { return Set.of("mob_disarmer", "phantom_obliterator").contains(localId); }
        public Optional<String> coreItemBehavior(String localId) {
            return Optional.ofNullable(BukkitToolEnvironment.CORE_TOOL_BEHAVIORS.get(localId));
        }
        public boolean isParticle(String particle) { return BukkitParticles.resolve(particle).isPresent(); }
    };

    private static ToolDefinition parse(String resource) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = BundledToolDefinitionsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is bundled");
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        ToolDefinitionParser.Result result = ToolDefinitionParser.parse(resource, ToolDefinitionLoader.toMap(yaml), BEHAVIORS, ENV);
        assertTrue(result.valid(), result.diagnostics().toString());
        return result.definition();
    }

    @Test
    void thePhantomObliteratorDefaultIsThePreRecodeTool() throws Exception {
        ToolDefinition definition = parse("tools/phantom_obliterator.yml");
        assertEquals("allium:phantom_obliterator", definition.id());
        assertEquals(ObliterateBehaviorType.ID, definition.behavior());
        assertTrue(definition.enabled());
        assertEquals(ProviderKey.allium("phantom_obliterator"), definition.appearance());
        ObliterateParameters parameters = definition.parameters(ObliterateParameters.class);
        assertEquals(3, parameters.rules().maxCharges());
        assertEquals(120_000L, parameters.rules().rechargeMillis());
        assertEquals(0.0D, parameters.rules().perChargeBreakChance());
        assertEquals(64.0D, parameters.radius());
        assertEquals(Set.of("minecraft:phantom"), parameters.entityTypes());
        assertTrue(parameters.requireUnnamed() && parameters.excludeNpcs() && parameters.attributedKill() && parameters.importLegacy());
        assertEquals(64, parameters.maxTargets());
        assertEquals("allium.phantomobliterator.use", definition.permission("use"));
        assertEquals("allium.phantomobliterator.bypass", definition.permission("bypass"));
        assertEquals(new ToolSound("minecraft:entity.evoker.cast_spell", 1.0F, 0.65F), definition.sound("obliterate"));
        assertEquals(List.of("soul_fire_flame", "reverse_portal"), definition.particles("target"));
        assertEquals(List.of("enchant"), definition.particles("user"));
        assertEquals("tool.phantom-obliterator.obliterated", definition.message("obliterated"));
    }

    @Test
    void theMobDisarmerDefaultStillParses() throws Exception {
        ToolDefinition definition = parse("tools/mob_disarmer.yml");
        assertEquals("allium:mob_disarmer", definition.id());
        assertTrue(definition.particles().isEmpty(), "the disarm behavior has no particle slots");
    }

    @Test
    void theSameParticleNamesResolveOnThisApi() {
        for (String name : List.of("soul_fire_flame", "reverse_portal", "enchant")) {
            assertTrue(BukkitParticles.resolve(name).isPresent(), name);
        }
        assertTrue(BukkitParticles.resolve("not_a_particle").isEmpty());
        assertTrue(BukkitParticles.resolve("dust").isEmpty(), "dust needs data, so it is refused");
    }
}
