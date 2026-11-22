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
import codes.castled.allium.tool.felltree.FellTreeBehaviorType;
import codes.castled.allium.tool.felltree.FellTreeParameters;
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
            ObliterateBehaviorType.ID, new ObliterateBehaviorType(),
            FellTreeBehaviorType.ID, new FellTreeBehaviorType(),
            codes.castled.allium.tool.powertool.PowerToolBehaviorType.ID, new codes.castled.allium.tool.powertool.PowerToolBehaviorType());

    private static final ToolEnvironment ENV = new ToolEnvironment() {
        public boolean isItemMaterial(String material) { return true; }
        public boolean isEntityType(String entityType) { return entityType.startsWith("minecraft:"); }
        public boolean hasLangKey(String key) { return true; }
        public boolean isCoreItem(String localId) { return Set.of("mob_disarmer", "phantom_obliterator", "lazy_axe", "handcuffs", "claim_handcuffs").contains(localId); }
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

    @Test
    void theLazyAxeDefaultParsesAndIsDisabledByDefault() throws Exception {
        ToolDefinition definition = parse("tools/lazy_axe.yml");
        assertEquals("allium:lazy_axe", definition.id());
        assertEquals(FellTreeBehaviorType.ID, definition.behavior());
        assertFalse(definition.enabled(), "Lazy Axe is disabled by default");
        assertEquals(ProviderKey.allium("lazy_axe"), definition.appearance());
        FellTreeParameters parameters = definition.parameters(FellTreeParameters.class);
        assertEquals(256, parameters.maxBlocks());
        assertEquals(8, parameters.maxRadius());
        assertEquals(48, parameters.maxHeight());
        assertEquals(4, parameters.minNaturalLeaves());
        assertEquals(6, parameters.blocksPerTick());
        assertTrue(parameters.breakAsPlayer());
        assertTrue(parameters.stopBeforeBreak());
        assertEquals("disables", parameters.sneak());
        assertTrue(parameters.importLegacy());
        assertEquals("allium.treeaxe.use", definition.permission("use"));
    }

    @Test
    void theHandcuffsArePreRecodeAlliumsAsAPowertool() throws Exception {
        ToolDefinition definition = parse("tools/handcuffs.yml");
        assertEquals("allium:handcuffs", definition.id());
        assertEquals(codes.castled.allium.tool.powertool.PowerToolBehaviorType.ID, definition.behavior());
        assertTrue(definition.enabled());
        assertEquals(ProviderKey.allium("handcuffs"), definition.appearance(), "the core pack's fishing rod");
        assertEquals("allium.restrain", definition.permission("use"));
        var parameters = definition.parameters(codes.castled.allium.tool.powertool.PowerToolParameters.class);
        var fish = parameters.binding(codes.castled.allium.tool.powertool.PowerToolTrigger.ON_FISH);
        assertEquals(List.of("caught-entity"), fish.fishing());
        assertTrue(fish.cancelInteraction(), "the rod does not pull a hooked player");
        assertTrue(fish.matchesFishing("CAUGHT_ENTITY"));
        assertFalse(fish.matchesFishing("FISHING"));
        var ride = fish.actions().get(0);
        assertEquals("ride", ride.type());
        assertTrue(ride.required());
        assertEquals(true, ride.params().get("restrain"));
        assertEquals(5, ride.params().get("max-riders"));
        assertEquals("allium.handcuffs.resist", ride.params().get("exempt-permission"));
        var drop = parameters.binding(codes.castled.allium.tool.powertool.PowerToolTrigger.DROP);
        assertFalse(drop.cancelInteraction(), "idle handcuffs drop as usual");
        assertEquals("release", drop.actions().get(0).type());
        assertEquals("all", drop.actions().get(0).params().get("which"));
    }

    @Test
    void theClaimHandcuffsRestrainOneUntrustedPlayerThenCountDownToAClaimBan() throws Exception {
        ToolDefinition definition = parse("tools/claim_handcuffs.yml");
        assertEquals("allium:claim_handcuffs", definition.id());
        assertEquals(ProviderKey.allium("claim_handcuffs"), definition.appearance());
        assertEquals("allium.claimhandcuffs.use", definition.permission("use"));
        var parameters = definition.parameters(codes.castled.allium.tool.powertool.PowerToolParameters.class);
        var fish = parameters.binding(codes.castled.allium.tool.powertool.PowerToolTrigger.ON_FISH);
        assertEquals(List.of("caught-entity"), fish.fishing());
        var ride = fish.actions().get(0);
        assertEquals("ride", ride.type());
        assertTrue(ride.required(), "no countdown unless the claim check and restraint worked");
        assertEquals(true, ride.params().get("claim"));
        assertEquals(1, ride.params().get("max-riders"));
        assertEquals("claim_ban", fish.actions().get(1).type());
        assertEquals(5, fish.actions().get(1).params().get("countdown-seconds"));
        assertEquals("release", parameters.binding(codes.castled.allium.tool.powertool.PowerToolTrigger.DROP).actions().get(0).type());
    }
}
