package codes.castled.allium.listeners.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandPathTest {
    @Test
    void reconstructsRootSubcommandAndArgumentSuggestions() {
        assertEquals("party", CommandPath.suggestion("/pa", "party", 1));
        assertEquals("party invite", CommandPath.suggestion("/party in", "invite", 7));
        assertEquals("party invite steve", CommandPath.suggestion("/party invite St", "Steve", 14));
        assertEquals("party admin invite", CommandPath.suggestion("/party admin in", "admin invite", 7));
    }

    @Test
    void fallbackRangeHandlesTrailingWhitespaceAndLegacyFullCommandSuggestions() {
        assertEquals("party invite", CommandPath.suggestion("/party ", "invite", -1));
        assertEquals("party invite", CommandPath.suggestion("/party\t", "invite", -1));
        assertEquals("party invite steve", CommandPath.suggestion("/party invite St", "Steve", 999));
        assertEquals("party", CommandPath.suggestion("/pa", "/party", 0));
    }
}
