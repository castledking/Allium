package codes.castled.allium.command.spec;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisciplineCommandSpecTest {
    private static CommandSpec spec(String name) {
        return CoreCommandSpecs.DISCIPLINE_SPECS.stream()
                .filter(s -> s.canonicalName().equals(name)).findFirst().orElseThrow();
    }

    @Test void completionUsesKnownNamesAndLoadedJailsAndHonorsBasePermission() {
        CommandCompletionContext allowed = new CommandCompletionContext() {
            public boolean hasPermission(String node) { return true; }
            public List<String> visibleOnlinePlayers() { return List.of("Visible"); }
            public List<String> knownPlayers(String prefix) { return List.of("OfflineKnown", "Visible"); }
            public List<String> jailNames(String prefix) { return List.of("cell", "spawncell"); }
        };
        assertEquals(List.of("OfflineKnown"), spec("ban").complete(allowed, new String[]{"Off"}));
        assertEquals(List.of("cell"), spec("jail").complete(allowed, new String[]{"OfflineKnown", "ce"}));
        assertEquals(List.of("on", "off"), spec("freeze").complete(allowed, new String[]{"Visible", "o"}));
        CommandCompletionContext denied = new CommandCompletionContext() {
            public boolean hasPermission(String node) { return false; }
            public List<String> visibleOnlinePlayers() { fail("Denied completion must not inspect names"); return List.of(); }
        };
        assertTrue(spec("ban").complete(denied, new String[]{""}).isEmpty());
    }

    @Test void disablingPardonDisablesTheWholeAlliumUnbanFamily() {
        CommandFamilyPolicy policy = new CommandFamilyPolicy();
        policy.registerSpecs(CoreCommandSpecs.MODERATION_SPECS);
        policy.initializeStartup(List.of("pardon"));
        assertTrue(policy.isDisabled("unban"));
        assertTrue(policy.isDisabled("pardon"));
        assertTrue(policy.isDisabled("allium:unban"));
        assertTrue(policy.isDisabled("allium:pardon"));
        assertFalse(policy.isDisabled("ban"));
        assertEquals("allium.unban", spec("unban").basePermission());
    }
}
