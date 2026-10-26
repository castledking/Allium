package codes.castled.allium.managers.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ChatHistoryClearTest {
    @Test void configuredEntriesAreSeparateDistinctBlankMessages() {
        List<Component> entries = new ArrayList<>();
        ChatHistoryClear.send(500, entries::add);
        assertEquals(500, entries.size());
        assertEquals(500, new HashSet<>(entries).size());
        assertTrue(entries.stream().allMatch(ChatHistoryClear::isWhitespace));
        assertTrue(entries.stream().noneMatch(c -> PlainTextComponentSerializer.plainText().serialize(c).contains("\n")));
    }

    @Test void disabledAndBoundedConfigurationsAreHonored() {
        assertEquals(0, ChatHistoryClear.entryCount(0));
        assertEquals(0, ChatHistoryClear.entryCount(-5));
        assertEquals(100, ChatHistoryClear.entryCount(1));
        assertEquals(500, ChatHistoryClear.entryCount(500));
        assertEquals(4096, ChatHistoryClear.entryCount(Integer.MAX_VALUE));
    }
}
