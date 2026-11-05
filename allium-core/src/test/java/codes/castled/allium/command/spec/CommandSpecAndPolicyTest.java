package codes.castled.allium.command.spec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSpecAndPolicyTest {

    @Test
    void disablingAliasOrNamespacedFormDisablesEntireFamily() {
        CommandFamilyPolicy policy = new CommandFamilyPolicy();
        policy.registerSpecs(CoreCommandSpecs.ESSENTIALS_PROOF_SPECS);

        CommandFamilyPolicy.PolicyEvaluation eval = policy.initializeStartup(List.of(" /GM ", "ALLIUM:HEAL", "unknown_cmd", "/unknown_cmd"));

        assertEquals(Set.of("gamemode", "heal"), eval.activeDisabledFamilies());
        assertEquals(List.of("unknown_cmd"), eval.unknownEntries());

        // Canonical, alias, and allium:<label> forms are all disabled
        assertTrue(policy.isDisabled("gamemode"));
        assertTrue(policy.isDisabled("/GAMEMODE"));
        assertTrue(policy.isDisabled("gm"));
        assertTrue(policy.isDisabled("allium:gamemode"));
        assertTrue(policy.isDisabled("allium:gm"));

        assertTrue(policy.isDisabled("heal"));
        assertTrue(policy.isDisabled("allium:heal"));

        // Unaffected families remain enabled
        assertFalse(policy.isDisabled("feed"));
        assertFalse(policy.isDisabled("fly"));
        assertFalse(policy.isDisabled("god"));

        // Non-Allium commands are never claimed as disabled by Allium
        assertFalse(policy.isDisabled("unknown_cmd"));
        assertFalse(policy.isDisabled("minecraft:give"));
    }

    @Test
    void reloadBlocksImmediatelyAndReportsRestartRequirementForCommandMapDeltas() {
        CommandFamilyPolicy policy = new CommandFamilyPolicy();
        policy.registerSpecs(CoreCommandSpecs.ESSENTIALS_PROOF_SPECS);

        // Startup: heal is disabled
        CommandFamilyPolicy.PolicyEvaluation startup = policy.initializeStartup(List.of("heal"));
        assertFalse(startup.requiresRestartForCommandMap());
        assertTrue(policy.isDisabled("heal"));
        assertTrue(policy.isDisabledAtStartup("heal"));

        // Reload: heal removed from disabled-commands, fly and gm added
        CommandFamilyPolicy.PolicyEvaluation reload = policy.applyReload(List.of("fly", "/allium:gm"));
        assertTrue(reload.requiresRestartForCommandMap());
        assertEquals(Set.of("fly", "gamemode"), reload.newlyDisabledRequiringRestartToYield());
        assertEquals(Set.of("heal"), reload.newlyEnabledRequiringRestartToClaim());

        // Active dispatch state reflects reload immediately
        assertTrue(policy.isDisabled("fly"));
        assertTrue(policy.isDisabled("gm"));
        assertTrue(policy.isDisabled("gamemode"));
        assertFalse(policy.isDisabled("heal"));
    }

    @Test
    void tabCompletionRespectsBaseAndOthersPermissionsAndPrefixFiltering() {
        CommandCompletionContext noPerms = context(Set.of(), List.of("Alice", "Alex", "Bob"));
        CommandCompletionContext selfOnly = context(
                Set.of("allium.heal", "allium.gamemode"),
                List.of("Alice", "Alex", "Bob")
        );
        CommandCompletionContext withOthers = context(
                Set.of("allium.heal", "allium.heal.others", "allium.gamemode", "allium.gamemode.others"),
                List.of("Alice", "Alex", "Bob")
        );

        // 1. Without base permission: no completions
        assertEquals(List.of(), CoreCommandSpecs.HEAL.complete(noPerms, new String[]{""}));
        assertEquals(List.of(), CoreCommandSpecs.GAMEMODE.complete(noPerms, new String[]{""}));

        // 2. With base permission only (no .others):
        //    - /heal [player] does NOT suggest other players
        assertEquals(List.of(), CoreCommandSpecs.HEAL.complete(selfOnly, new String[]{""}));
        //    - /gamemode <mode> [player] suggests modes at arg 0, but NOT players at arg 1
        assertEquals(
                List.of("survival", "creative", "adventure", "spectator"),
                CoreCommandSpecs.GAMEMODE.complete(selfOnly, new String[]{""})
        );
        assertEquals(
                List.of("survival", "spectator"),
                CoreCommandSpecs.GAMEMODE.complete(selfOnly, new String[]{"s"})
        );
        assertEquals(
                List.of(),
                CoreCommandSpecs.GAMEMODE.complete(selfOnly, new String[]{"creative", ""})
        );

        // 3. With .others permission: suggests visible online players filtered by prefix
        assertEquals(
                List.of("Alice", "Alex", "Bob"),
                CoreCommandSpecs.HEAL.complete(withOthers, new String[]{""})
        );
        assertEquals(
                List.of("Alice", "Alex"),
                CoreCommandSpecs.HEAL.complete(withOthers, new String[]{"al"})
        );
        assertEquals(
                List.of("Bob"),
                CoreCommandSpecs.GAMEMODE.complete(withOthers, new String[]{"creative", "b"})
        );
    }

    @Test
    void subcommandTreeCompletesChildrenAndArgumentsByPermission() {
        CommandSpec mailSpec = new CommandSpec(
                "mail",
                "allium",
                "chat",
                List.of(),
                "Mail command family",
                "/mail <send|gift>",
                "/mail <send|gift>",
                "allium.mail",
                "allium.mail.others",
                List.of(),
                List.of(
                        new CommandNodeSpec(
                                "send",
                                List.of(),
                                "allium.mail.send",
                                "/mail send <player>",
                                List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null))),
                                List.of()
                        ),
                        new CommandNodeSpec(
                                "gift",
                                List.of(),
                                "allium.mail.gift",
                                "/mail gift <player>",
                                List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null))),
                                List.of()
                        )
                ),
                CommandMessageKeys.ofOutcomes(Map.of())
        );

        CommandCompletionContext sendOnly = context(
                Set.of("allium.mail", "allium.mail.send"),
                List.of("Alice", "Bob")
        );
        assertEquals(List.of("send"), mailSpec.complete(sendOnly, new String[]{""}));
        assertEquals(List.of("Alice"), mailSpec.complete(sendOnly, new String[]{"send", "a"}));
        assertEquals(List.of(), mailSpec.complete(sendOnly, new String[]{"gift", "a"}));
    }

    private static CommandCompletionContext context(Set<String> permissions, List<String> visiblePlayers) {
        return new CommandCompletionContext() {
            @Override
            public boolean hasPermission(String permissionNode) {
                return permissions.contains(permissionNode);
            }

            @Override
            public List<String> visibleOnlinePlayers() {
                return visiblePlayers;
            }
        };
    }
}
