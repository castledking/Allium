package codes.castled.allium.tool.powertool;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.definition.BuiltInItemSpec;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolDefinition;
import codes.castled.allium.tool.definition.ToolDefinitionParser;
import codes.castled.allium.tool.definition.ToolEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PowerToolCoreTest {

    private static final ToolEnvironment TEST_ENV = new ToolEnvironment() {
        @Override public boolean isCoreItem(String localId) { return false; }
        @Override public Optional<String> coreItemBehavior(String localId) { return Optional.empty(); }
        @Override public boolean isItemMaterial(String material) { return Set.of("diamond_axe", "blaze_rod", "golden_hoe", "stick").contains(material.toLowerCase()); }
        @Override public boolean isEntityType(String type) { return true; }
        @Override public boolean isParticle(String particle) { return true; }
        @Override public boolean hasLangKey(String key) { return true; }
    };

    @Test
    void durationsParseAndFormatAccurately() {
        assertEquals(0L, PowerToolDurations.parseMillis("0"));
        assertEquals(500L, PowerToolDurations.parseMillis("500ms"));
        assertEquals(1000L, PowerToolDurations.parseMillis("20t"));
        assertEquals(5000L, PowerToolDurations.parseMillis("5s"));
        assertEquals(5000L, PowerToolDurations.parseMillis("5"));
        assertEquals(90_000L, PowerToolDurations.parseMillis("1m30s"));
        assertEquals(3_600_000L, PowerToolDurations.parseMillis("1h"));
        assertEquals(20L, PowerToolDurations.millisToTicks(1000L));
        assertEquals(1L, PowerToolDurations.millisToTicks(10L));
        assertEquals("5s", PowerToolDurations.formatMillis(5000L));
        assertEquals("1m30s", PowerToolDurations.formatMillis(90_000L));
        assertEquals("500ms", PowerToolDurations.formatMillis(500L));
        assertThrows(IllegalArgumentException.class, () -> PowerToolDurations.parseMillis("abc"));
        assertThrows(IllegalArgumentException.class, () -> PowerToolDurations.parseMillis("5x"));
    }

    @Test
    void securityChecksAllowlistSelectorsAndPlaceholders() {
        List<String> allow = List.of(
                "allium:", "minecraft:effect", "minecraft:particle",
                "minecraft:playsound", "minecraft:title", "minecraft:tellraw"
        );
        assertTrue(PowerToolSecurity.isConsoleCommandAllowed("allium:heal Steve", allow));
        assertTrue(PowerToolSecurity.isConsoleCommandAllowed("/minecraft:effect give Steve speed", allow));
        assertFalse(PowerToolSecurity.isConsoleCommandAllowed("op Steve", allow));
        assertFalse(PowerToolSecurity.isConsoleCommandAllowed("minecraft:op Steve", allow));
        assertTrue(PowerToolSecurity.isConsoleCommandAllowed("anything here", List.of("*")));

        assertTrue(PowerToolSecurity.containsSelectors("tp @a 0 64 0"));
        assertTrue(PowerToolSecurity.containsSelectors("kill @e[type=pig]"));
        assertFalse(PowerToolSecurity.containsSelectors("msg Steve hello@world.com"));

        assertNull(PowerToolSecurity.firstUnknownPlaceholder("allium:heal {player} at {x} {y} {z}"));
        assertEquals("evil", PowerToolSecurity.firstUnknownPlaceholder("say {evil}"));
        assertEquals("heal Alex in world", PowerToolSecurity.applyPlaceholders(
                "heal {player} in {world}", Map.of("player", "Alex", "world", "world")));

        assertTrue(PowerToolSecurity.isValidToolName("my_lumber_axe"));
        assertFalse(PowerToolSecurity.isValidToolName("x"));
        assertFalse(PowerToolSecurity.isValidToolName("../escape"));
    }

    @Test
    void cooldownTrackerRespectsScopesAndDebounce() {
        AtomicLong clock = new AtomicLong(10_000L);
        PowerToolCooldownTracker tracker = new PowerToolCooldownTracker(clock::get);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        // Click guard
        assertTrue(tracker.tryAcquireClickGuard(p1, PowerToolTrigger.RIGHT, 100L));
        assertFalse(tracker.tryAcquireClickGuard(p1, PowerToolTrigger.RIGHT, 100L));
        clock.addAndGet(100L);
        assertTrue(tracker.tryAcquireClickGuard(p1, PowerToolTrigger.RIGHT, 100L));

        // Player scope isolates p1 and p2
        tracker.setCooldown(p1, "allium:wand", "inst-1", PowerToolTrigger.RIGHT, "binding", PowerToolCooldownScope.PLAYER, null, 5000L);
        assertEquals(5000L, tracker.remainingMillis(p1, "allium:wand", "inst-1", PowerToolTrigger.RIGHT, "binding", PowerToolCooldownScope.PLAYER, null));
        assertEquals(0L, tracker.remainingMillis(p2, "allium:wand", "inst-1", PowerToolTrigger.RIGHT, "binding", PowerToolCooldownScope.PLAYER, null));

        // Global scope shares across players
        tracker.setCooldown(p1, "allium:wand", "inst-1", PowerToolTrigger.LEFT, "binding", PowerToolCooldownScope.GLOBAL, null, 3000L);
        assertEquals(3000L, tracker.remainingMillis(p2, "allium:wand", "inst-2", PowerToolTrigger.LEFT, "binding", PowerToolCooldownScope.GLOBAL, null));

        // Custom cooldown-id shares across tools for the same player
        tracker.setCooldown(p1, "allium:wand_a", "inst-1", PowerToolTrigger.RIGHT, "binding", PowerToolCooldownScope.PLAYER, "shared_dash", 4000L);
        assertEquals(4000L, tracker.remainingMillis(p1, "allium:wand_b", "inst-9", PowerToolTrigger.SHIFT_RIGHT, "binding", PowerToolCooldownScope.PLAYER, "shared_dash"));

        clock.addAndGet(5001L);
        assertEquals(0L, tracker.remainingMillis(p1, "allium:wand", "inst-1", PowerToolTrigger.RIGHT, "binding", PowerToolCooldownScope.PLAYER, null));
    }

    @Test
    void codecAndParserRoundTripCompletePowerToolDefinitionAndInlineJson() {
        PowerToolAction fellAction = new PowerToolAction(
                PowerToolAction.TYPE_TREE_FELL, null, false,
                0L, PowerToolCooldownScope.PLAYER, null, 0L, null,
                Map.of("max-blocks", 256, "blocks-per-tick", 6, "stop-before-break", true)
        );
        PowerToolAction msgAction = new PowerToolAction(
                PowerToolAction.TYPE_ACTIONBAR, "&aTimber!", false,
                0L, PowerToolCooldownScope.PLAYER, null, 10L, "allium.extra", Map.of()
        );
        PowerToolBinding onBreak = new PowerToolBinding(
                PowerToolTrigger.ON_BREAK, "allium.lumber.use", false,
                PowerToolBinding.HAND_MAIN, PowerToolBinding.SNEAK_DISALLOW, List.of("world"), List.of(),
                5000L, PowerToolCooldownScope.PLAYER, "lumber_cd", "[actionbar] &cWait {time}",
                true, true, List.of(fellAction, msgAction)
        );
        PowerToolParameters params = new PowerToolParameters(
                new PowerToolUses(5, false, 60, true),
                150L,
                Map.of(PowerToolTrigger.ON_BREAK, onBreak)
        );

        // Inline JSON round-trip
        String json = PowerToolCodec.encodeInlineJson(params, "allium.custom.use");
        PowerToolCodec.DecodedInline decoded = PowerToolCodec.decodeInlineJson(json);
        assertEquals("allium.custom.use", decoded.usePermission());
        assertEquals(5, decoded.parameters().uses().max());
        assertEquals(60, decoded.parameters().uses().rechargeSeconds());
        PowerToolBinding decodedBinding = decoded.parameters().binding(PowerToolTrigger.ON_BREAK);
        assertNotNull(decodedBinding);
        assertEquals(2, decodedBinding.actions().size());
        assertEquals(PowerToolAction.TYPE_TREE_FELL, decodedBinding.actions().get(0).type());
        assertEquals(10L, decodedBinding.actions().get(1).delayTicks());
        assertEquals("lumber_cd", decodedBinding.cooldownId());
        assertTrue(decodedBinding.visualCooldown());

        // Full tools/<name>.yml document round-trip through ToolDefinitionParser
        BuiltInItemSpec itemSpec = new BuiltInItemSpec(
                "diamond_axe", "&6Lumber Axe", 1042,
                List.of("&7Chops whole trees!", ""),
                Map.of("efficiency", 5),
                true,
                List.of("HIDE_ATTRIBUTES")
        );
        Map<String, Object> doc = PowerToolCodec.toDefinitionDocument(
                "lumber_axe", "Lumber Axe", itemSpec, ProviderKey.allium("lumber_axe"),
                params, "allium.tool.lumber_axe.use", "allium.tool.lumber_axe.bypass"
        );
        ToolDefinitionParser.Result parsed = ToolDefinitionParser.parse(
                "tools/lumber_axe.yml", doc, Map.of(PowerToolBehaviorType.ID, new PowerToolBehaviorType()), TEST_ENV);
        assertTrue(parsed.valid(), () -> "Errors: " + parsed.errors());
        ToolDefinition def = parsed.definition();
        assertEquals("allium:lumber_axe", def.id());
        assertEquals(PowerToolBehaviorType.ID, def.behavior());
        assertNotNull(def.item());
        assertEquals(List.of("&7Chops whole trees!", ""), def.item().lore());
        assertEquals(Map.of("efficiency", 5), def.item().enchantments());
        assertTrue(def.item().unbreakable());
        PowerToolParameters parsedParams = def.parameters(PowerToolParameters.class);
        assertEquals(5, parsedParams.uses().max());
        assertEquals(2, parsedParams.binding(PowerToolTrigger.ON_BREAK).actions().size());
    }

    @Test
    void parserRejectsInvalidTriggerAndZeroUses() {
        Map<String, Object> badDoc = Map.of(
                "schema", 1,
                "id", "allium:bad_pt",
                "behavior", PowerToolBehaviorType.ID,
                "item", Map.of("material", "stick"),
                "uses", Map.of("max", 0),
                "bindings", Map.of(
                        "not_a_trigger", Map.of("actions", List.of(Map.of("type", "message", "value", "hi")))
                )
        );
        ToolDefinitionParser.Result res = ToolDefinitionParser.parse(
                "tools/bad_pt.yml", badDoc, Map.of(PowerToolBehaviorType.ID, new PowerToolBehaviorType()), TEST_ENV);
        assertFalse(res.valid());
        assertTrue(res.errors().stream().anyMatch(e -> e.contains("uses.max")));
        assertTrue(res.errors().stream().anyMatch(e -> e.contains("not_a_trigger")));
    }

    @Test
    void fishingStatesAreAConditionOfTheOnFishTriggerAndRoundTrip() {
        assertEquals(PowerToolTrigger.ON_FISH, PowerToolTrigger.parse("fish"));
        assertEquals(PowerToolTrigger.ON_FISH, PowerToolTrigger.parse("on_fish"));
        assertEquals("caught-entity", PowerToolBinding.fishState("CAUGHT_ENTITY"));
        assertEquals("caught-entity", PowerToolBinding.fishState("hooked"));
        assertEquals("cast", PowerToolBinding.fishState("FISHING"));
        assertEquals("failed", PowerToolBinding.fishState("FAILED_ATTEMPT"));
        assertEquals("in-ground", PowerToolBinding.fishState("IN_GROUND"));
        assertEquals("reel-in", PowerToolBinding.fishState("REEL_IN"));
        assertNull(PowerToolBinding.fishState("splash"));
        for (String state : PowerToolBinding.FISH_STATES) {
            assertEquals(state, PowerToolBinding.fishState(state), state);
        }

        PowerToolBinding any = PowerToolBinding.empty(PowerToolTrigger.ON_FISH);
        assertTrue(any.matchesFishing("BITE") && any.matchesFishing("CAUGHT_ENTITY"), "no states: every state");
        PowerToolBinding hooked = any.withFishing(List.of("hooked", "REEL_IN", "nonsense"))
                .withActionAdded(new PowerToolAction("ride", null, false, 0L, PowerToolCooldownScope.PLAYER, null, 0L, null,
                        Map.of("restrain", true)));
        assertEquals(List.of("caught-entity", "reel-in"), hooked.fishing(), "canonical, unknown ones dropped");
        assertTrue(hooked.matchesFishing("CAUGHT_ENTITY"));
        assertFalse(hooked.matchesFishing("FISHING"));
        assertFalse(hooked.matchesFishing(null));

        PowerToolParameters params = new PowerToolParameters(PowerToolUses.UNLIMITED, 150L, Map.of(PowerToolTrigger.ON_FISH, hooked));
        PowerToolBinding back = PowerToolCodec.decodeInlineJson(PowerToolCodec.encodeInlineJson(params, null))
                .parameters().binding(PowerToolTrigger.ON_FISH);
        assertEquals(List.of("caught-entity", "reel-in"), back.fishing());
        assertEquals(PowerToolAction.TYPE_RIDE, back.actions().get(0).type());
        assertEquals(true, back.actions().get(0).params().get("restrain"));
    }

    @Test
    void theParserRefusesUnknownFishingStatesAndFishingOnOtherTriggers() {
        Map<String, Object> doc = Map.of(
                "schema", 1,
                "id", "allium:bad_rod",
                "behavior", PowerToolBehaviorType.ID,
                "item", Map.of("material", "stick"),
                "bindings", Map.of(
                        "on-fish", Map.of("fishing", List.of("splash"), "actions", List.of(Map.of("type", "ride"))),
                        "right", Map.of("fishing", List.of("cast"), "actions", List.of(Map.of("type", "release")))
                )
        );
        ToolDefinitionParser.Result res = ToolDefinitionParser.parse(
                "tools/bad_rod.yml", doc, Map.of(PowerToolBehaviorType.ID, new PowerToolBehaviorType()), TEST_ENV);
        assertFalse(res.valid());
        assertTrue(res.errors().stream().anyMatch(e -> e.contains("unknown fishing state 'splash'")), res.errors().toString());
        assertTrue(res.errors().stream().anyMatch(e -> e.contains("only applies to the on-fish trigger")), res.errors().toString());
    }

    @Test
    void rideMountAndReleaseAreBuiltInActionsWithFriendlyNames() {
        assertTrue(PowerToolAction.BUILT_IN_TYPES.containsAll(List.of("ride", "mount", "release")));
        assertEquals(PowerToolAction.TYPE_RIDE, PowerToolAction.normalizeType("carry"));
        assertEquals(PowerToolAction.TYPE_RIDE, PowerToolAction.normalizeType("allium:ride"));
        assertEquals(PowerToolAction.TYPE_MOUNT, PowerToolAction.normalizeType("ride-target"));
        assertEquals(PowerToolAction.TYPE_RELEASE, PowerToolAction.normalizeType("unrestrain"));
        assertEquals(PowerToolAction.TYPE_RELEASE, PowerToolAction.normalizeType("uncuff"));
    }
}
