package codes.castled.allium.listeners.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CommandVisibilityRulesTest {
    @Test
    void whitelistSubcommandAllowsOnlyItsExecutionBranchAndArguments() {
        var group = group(true, List.of("party invite"), List.of());

        assertTrue(group.isCommandAllowed("/PARTY  invite Steve"));
        assertTrue(group.isCommandAllowed("allium:party invite Steve"));
        assertFalse(group.isCommandAllowed("party"));
        assertFalse(group.isCommandAllowed("party kick Steve"));
        assertFalse(group.isCommandAllowed("party invited"));
        assertFalse(group.isTabCompletionAllowed("party invite"));
    }

    @Test
    void completionOnlyWhitelistExposesAncestorsAndAllowedDescendants() {
        var group = group(true, List.of(), List.of("party admin invite"));

        assertTrue(group.isTabCompletionAllowed("party"));
        assertTrue(group.isTabCompletionAllowed("party admin"));
        assertTrue(group.isTabCompletionAllowed("party admin invite Steve"));
        assertFalse(group.isTabCompletionAllowed("party admin kick"));
        assertFalse(group.isTabCompletionAllowed("party leave"));
        assertFalse(group.isCommandAllowed("party admin invite Steve"));
        assertEquals(Set.of("party"), CommandManager.filterRootCommands(
                List.of("party", "spawn"), List.of(group), false, false));
    }

    @Test
    void blacklistSubcommandsDoNotHideTheRootOrUnrelatedBranches() {
        var group = group(false, List.of("party kick"), List.of("party invite"));

        assertTrue(group.isCommandAllowed("party invite Steve"));
        assertFalse(group.isCommandAllowed("party kick Steve"));
        assertTrue(group.isTabCompletionAllowed("party"));
        assertTrue(group.isTabCompletionAllowed("party kick Steve"));
        assertFalse(group.isTabCompletionAllowed("party invite"));
        assertFalse(group.isTabCompletionAllowed("allium:party invite Steve"));
        assertTrue(group.isTabCompletionAllowed("party invited"));
    }

    @Test
    void completionBlacklistWinsOverAnotherGroupsWhitelist() {
        var whitelist = group(true, List.of(), List.of("party"));
        var blacklist = group(false, List.of(), List.of("party kick"));
        List<CommandManager.CommandGroup> groups = List.of(whitelist, blacklist);

        assertTrue(CommandManager.shouldAllowTabComplete(groups, "party"));
        assertTrue(CommandManager.shouldAllowTabComplete(groups, "party invite Steve"));
        assertFalse(CommandManager.shouldAllowTabComplete(groups, "party kick Steve"));
    }

    @Test
    void rootAndWildcardRulesStillCoverAllArguments() {
        var roots = group(true, List.of("msg"), List.of("msg"));
        assertTrue(roots.isCommandAllowed("msg Steve hello"));
        assertTrue(roots.isTabCompletionAllowed("msg Steve"));
        assertTrue(roots.isTabCompletionAllowed("msg Steve minecraft:stone"));
        assertFalse(roots.isTabCompletionAllowed("msgg Steve"));
        assertTrue(group(true, List.of("*"), List.of("*")).isTabCompletionAllowed("any sub arg"));
        assertFalse(group(false, List.of("*"), List.of("*")).isTabCompletionAllowed("any sub arg"));
    }

    @Test
    void inheritedCommandPathsRetainTheirBranchBoundaries() {
        var group = new CommandManager.CommandGroup("alt", false, List.of("party invite"),
                List.of("party invite", "party leave"), List.of(), false);
        assertFalse(group.isCommandAllowed("party invite Steve"));
        assertTrue(group.isCommandAllowed("party leave"));
        assertFalse(group.isCommandAllowed("party kick Steve"));
    }

    @Test
    void bukkitSuggestionsAreFilteredAsPathsInsteadOfIndividualArguments() {
        var group = group(true, List.of(), List.of("party invite"));
        List<String> suggestions = List.of("invite", "kick", "leave");
        assertEquals(List.of("invite"), suggestions.stream().filter(value ->
                CommandManager.shouldAllowTabComplete(List.of(group),
                        CommandPath.suggestion("/party ", value, -1))).toList());
        assertTrue(CommandManager.shouldAllowTabComplete(List.of(group),
                CommandPath.suggestion("/party invite St", "Steve", -1)));
    }

    private static CommandManager.CommandGroup group(boolean whitelist, List<String> commands,
                                                      List<String> completions) {
        return new CommandManager.CommandGroup("test", whitelist, commands, List.of(), completions, false);
    }

    @Test
    void executableWhitelistCommandsStayHiddenUnlessListedForTabCompletion() {
        CommandManager.CommandGroup defaults = new CommandManager.CommandGroup(
                "default",
                true,
                List.of("dispose", "warp", "customtext", "spawn"),
                List.of(),
                List.of("spawn"),
                false);

        Set<String> visible = CommandManager.filterRootCommands(
                List.of("dispose", "warp", "customtext", "spawn"),
                List.of(defaults),
                false,
                false);

        assertEquals(Set.of("spawn"), visible);
    }

    @Test
    void tabCompletionListMayExposeACommandIndependentlyOfExecutionList() {
        CommandManager.CommandGroup defaults = new CommandManager.CommandGroup(
                "default", true, List.of("spawn"), List.of(), List.of("warp"), false);

        assertEquals(Set.of("warp"), CommandManager.filterRootCommands(
                List.of("spawn", "warp"), List.of(defaults), false, false));
    }

    @Test
    void blacklistTabCompletionRulesHideRootsIndependentlyOfCommandRules() {
        CommandManager.CommandGroup restricted = new CommandManager.CommandGroup(
                "restricted", false, List.of("plugins"), List.of(), List.of("warp"), false);

        assertEquals(Set.of("spawn"), CommandManager.filterRootCommands(
                List.of("spawn", "warp"), List.of(restricted), false, false));
    }
}
