package codes.castled.allium.item.spec;

import codes.castled.allium.item.ProviderKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GiveItemPolicyTest {

    @Test
    void denylistModeSupportsExactScopedProviderWildcardsExceptionsAndFailClosedTies() {
        GiveItemPolicy policy = new GiveItemPolicy(
                GiveItemPolicy.Mode.DENYLIST,
                List.of("bedrock", "minecraft:*_spawn_egg", "allium:*", "craftengine:sf_ores:*", "nexo:AdminBlade"),
                List.of("allium:grapple_hook", "minecraft:pig_spawn_egg", "bedrock"),
                false
        );

        // Exact tie between deny ("bedrock") and allow ("bedrock") -> deny wins (fail-closed)
        assertFalse(policy.evaluate(ProviderKey.minecraft("bedrock"), false, p -> false).allowed());

        // Specific allow carves out an exception to broader provider or scoped wildcards
        assertTrue(policy.evaluate(ProviderKey.allium("grapple_hook"), false, p -> false).allowed());
        assertFalse(policy.evaluate(ProviderKey.allium("handcuffs"), false, p -> false).allowed());
        assertTrue(policy.evaluate(ProviderKey.minecraft("pig_spawn_egg"), false, p -> false).allowed());
        assertFalse(policy.evaluate(ProviderKey.minecraft("creeper_spawn_egg"), false, p -> false).allowed());

        // CraftEngine nested namespace wildcard
        assertFalse(policy.evaluate(ProviderKey.of("craftengine", "sf_ores:silver_ingot"), false, p -> false).allowed());
        assertTrue(policy.evaluate(ProviderKey.of("craftengine", "sf_decor:chair"), false, p -> false).allowed());

        // Case-sensitive external provider (Nexo)
        assertFalse(policy.evaluate(ProviderKey.of("nexo", "AdminBlade"), false, p -> false).allowed());
        assertTrue(policy.evaluate(ProviderKey.of("nexo", "RubySword"), false, p -> false).allowed());

        // Bypass overrides all rules
        assertTrue(policy.evaluate(ProviderKey.minecraft("bedrock"), true, p -> false).allowed());
        assertTrue(policy.evaluate(ProviderKey.allium("handcuffs"), true, p -> false).allowed());
    }

    @Test
    void allowlistModeRefusesUnlistedProvidersAndHonorsMoreSpecificDenies() {
        GiveItemPolicy policy = new GiveItemPolicy(
                GiveItemPolicy.Mode.ALLOWLIST,
                List.of("minecraft:command_block", "craftengine:sf_ores:debug_*"),
                List.of("minecraft:*", "craftengine:sf_ores:*"),
                false
        );

        assertTrue(policy.evaluate(ProviderKey.minecraft("diamond"), false, p -> false).allowed());
        assertFalse(policy.evaluate(ProviderKey.minecraft("command_block"), false, p -> false).allowed());
        assertTrue(policy.evaluate(ProviderKey.of("craftengine", "sf_ores:ruby"), false, p -> false).allowed());
        assertFalse(policy.evaluate(ProviderKey.of("craftengine", "sf_ores:debug_pick"), false, p -> false).allowed());
        assertFalse(policy.evaluate(ProviderKey.allium("grapple_hook"), false, p -> false).allowed());
    }

    @Test
    void permissionBasedSpawnChecksExactSubNamespaceProviderAndGlobalNodes() {
        GiveItemPolicy policy = new GiveItemPolicy(GiveItemPolicy.Mode.DENYLIST, List.of(), List.of(), true);

        ProviderKey diamond = ProviderKey.minecraft("diamond");
        ProviderKey ore = ProviderKey.of("craftengine", "sf_ores:silver_ingot");

        assertFalse(policy.evaluate(diamond, false, p -> false).allowed());
        assertTrue(policy.evaluate(diamond, false, Set.of("allium.give.item.minecraft.diamond")::contains).allowed());
        assertTrue(policy.evaluate(diamond, false, Set.of("allium.give.item.diamond")::contains).allowed());
        assertTrue(policy.evaluate(diamond, false, Set.of("allium.give.item.minecraft.*")::contains).allowed());
        assertTrue(policy.evaluate(diamond, false, Set.of("allium.give.item.*")::contains).allowed());

        assertFalse(policy.evaluate(ore, false, Set.of("allium.give.item.minecraft.*")::contains).allowed());
        assertTrue(policy.evaluate(ore, false, Set.of("allium.give.item.craftengine.sf_ores.silver_ingot")::contains).allowed());
        assertTrue(policy.evaluate(ore, false, Set.of("allium.give.item.craftengine.sf_ores.*")::contains).allowed());
        assertTrue(policy.evaluate(ore, false, Set.of("allium.give.item.craftengine.*")::contains).allowed());
        assertTrue(policy.evaluate(ore, true, p -> false).allowed());
    }

    @Test
    void rejectsInvalidModesMalformedRulesAndLegacyPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> GiveItemPolicy.Mode.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> GiveItemPolicy.Rule.parse(""));
        assertThrows(IllegalArgumentException.class, () -> GiveItemPolicy.Rule.parse(":diamond"));
        assertThrows(IllegalArgumentException.class, () -> GiveItemPolicy.Rule.parse("minecraft:"));
        assertThrows(IllegalArgumentException.class, () -> GiveItemPolicy.Rule.parse("ci:handcuffs"));
        assertThrows(IllegalArgumentException.class, () -> GiveItemPolicy.Rule.parse("custom:sword"));
        // Deprecated ce: normalizes cleanly to craftengine:
        assertEquals("craftengine:sf_ores:*", GiveItemPolicy.Rule.parse("ce:sf_ores:*").raw());
    }
}
