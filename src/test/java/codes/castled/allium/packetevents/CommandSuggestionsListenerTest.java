package codes.castled.allium.packetevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CommandSuggestionsListenerTest {
    @Test
    void argumentCompletionReportsTheCommandBeingCompleted() {
        assertEquals("msg", CommandSuggestionsListener.commandBeingCompleted("/msg St"));
        assertEquals("tpa", CommandSuggestionsListener.commandBeingCompleted("/TPA "));
        assertEquals("trust", CommandSuggestionsListener.commandBeingCompleted("/trust Steve "));
        assertEquals("griefprevention:trust",
                CommandSuggestionsListener.commandBeingCompleted("/griefprevention:trust S"));
    }

    @Test
    void commandNameCompletionHasNoCommandBeingCompleted() {
        assertNull(CommandSuggestionsListener.commandBeingCompleted("/ms"));
        assertNull(CommandSuggestionsListener.commandBeingCompleted("/"));
        assertNull(CommandSuggestionsListener.commandBeingCompleted(""));
        assertNull(CommandSuggestionsListener.commandBeingCompleted("hello St"));
        assertNull(CommandSuggestionsListener.commandBeingCompleted(null));
    }
}
