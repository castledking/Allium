package codes.castled.allium.listeners.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CommandVisibilityRulesTest {
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
