package codes.castled.allium.tool.powertool;

import codes.castled.allium.tool.definition.ConfigReader;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerToolGrantsTest {

    @Test
    void weightsAreOptionalAndInvalidEntriesAreFound() {
        assertEquals(List.of(new PowerToolGrants.Choice(10, "allium.glow.aqua"), new PowerToolGrants.Choice(1, "allium.glow.black"),
                        new PowerToolGrants.Choice(1, "kits.vip")),
                PowerToolGrants.parse("10:allium.glow.aqua, 1:Allium.Glow.Black  kits.vip 5:kits.vip"));
        assertNull(PowerToolGrants.firstInvalid("10:a.b c.d"));
        assertEquals("x:a.b", PowerToolGrants.firstInvalid("10:a.b x:a.b"));
        assertEquals("0:a.b", PowerToolGrants.firstInvalid("0:a.b"));
        assertEquals("", PowerToolGrants.firstInvalid("  "));
    }

    @Test
    void onlyMissingNodesArePickedByWeightOrAllAtOnce() {
        List<PowerToolGrants.Choice> choices = PowerToolGrants.parse("90:a.common 10:a.rare 50:a.owned");
        Set<String> held = Set.of("a.owned");
        Map<String, Integer> picks = new HashMap<>();
        Random random = new Random(7L);
        for (int i = 0; i < 10_000; i++) {
            List<PowerToolGrants.Choice> picked = PowerToolGrants.select(choices, held::contains, false, random);
            assertEquals(1, picked.size());
            picks.merge(picked.get(0).node(), 1, Integer::sum);
        }
        assertFalse(picks.containsKey("a.owned"));
        assertTrue(picks.get("a.common") > 8_700 && picks.get("a.common") < 9_300, picks.toString());
        assertEquals(List.of("a.common", "a.rare"),
                PowerToolGrants.select(choices, held::contains, true, random).stream().map(PowerToolGrants.Choice::node).toList());
        assertEquals(List.of(), PowerToolGrants.select(choices, node -> true, false, random));
        assertTrue(PowerToolGrants.pickAll(Map.of("pick", "ALL")));
        assertFalse(PowerToolGrants.pickAll(Map.of()));
    }

    @Test
    void requiredActionsAndUnbypassableUsesSurviveTheYamlDocument() {
        PowerToolAction grant = new PowerToolAction(PowerToolAction.TYPE_GRANT, "2:a.b", false, 0L, PowerToolCooldownScope.PLAYER,
                null, 0L, null, Map.of("pick", "all"), true);
        PowerToolBinding binding = PowerToolBinding.empty(PowerToolTrigger.RIGHT).withActionAdded(grant);
        PowerToolParameters parameters = new PowerToolParameters(new PowerToolUses(1, true, 0, false, false), 0L,
                Map.of(PowerToolTrigger.RIGHT, binding));

        PowerToolParameters back = PowerToolCodec.decodeInlineJson(PowerToolCodec.encodeInlineJson(parameters, null)).parameters();

        PowerToolAction read = back.binding(PowerToolTrigger.RIGHT).actions().get(0);
        assertTrue(read.required());
        assertEquals(PowerToolAction.TYPE_GRANT, read.type());
        assertEquals("all", read.params().get("pick"));
        assertFalse(back.uses().bypassable());
        assertTrue(back.uses().consumeOnEmpty());
        assertEquals(PowerToolAction.TYPE_GRANT, PowerToolAction.normalizeType("permission"));
        assertTrue(read.describe().contains("[required]"));
    }

    @Test
    void aRequiredActionCannotBeDelayedAndAGrantNeedsValidNodes() {
        ConfigReader reader = ConfigReader.of(Map.of("bindings", Map.of("right", Map.of("actions", List.of(
                Map.of("type", "grant", "value", "5:ok.node x:bad", "required", true),
                Map.of("type", "message", "value", "hi", "required", true, "delay", "1s"))))));
        PowerToolBehaviorType.parseParameters(reader);
        String errors = String.join("\n", reader.errors());
        assertTrue(errors.contains("'x:bad' is not a permission node"), errors);
        assertTrue(errors.contains("a required action cannot have a delay"), errors);
    }
}
