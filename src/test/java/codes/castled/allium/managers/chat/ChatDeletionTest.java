package codes.castled.allium.managers.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ChatDeletionTest {
    private static final UUID SYSTEM = new UUID(0, 0);

    @Test void aLatePacketCopyCannotResurrectADeletedMessage() {
        ChatMessageManager manager = new ChatMessageManager();
        UUID sender = UUID.randomUUID(), viewer = UUID.randomUUID();
        Component text = Component.text("Player: delete this line");
        long id = manager.storeMessage(sender, "Player", text);
        manager.registerLogicalMessage(id, text);
        manager.trackMessageForPlayer(viewer, manager.storeMessageObject(SYSTEM, "SYSTEM", text));
        assertTrue(manager.deleteMessage(id));
        // Netty can finish capturing another copy after the command marked
        // the current copies deleted, or after replay suppression expires.
        manager.trackMessageForPlayer(viewer, manager.storeMessageObject(SYSTEM, "SYSTEM", text));
        assertTrue(manager.getPlayerChatHistory(viewer).isEmpty(),
            "A new packet object must inherit its logical message's deletion state");
    }

    @Test void deletingOneOccurrenceDoesNotAlsoDeleteALaterIdenticalMessage() {
        ChatMessageManager manager = new ChatMessageManager();
        UUID sender = UUID.randomUUID(), viewer = UUID.randomUUID();
        Component text = Component.text("Player: same words");
        long first = manager.storeMessage(sender, "Player", text);
        manager.registerLogicalMessage(first, text);
        var firstCopy = manager.storeMessageObject(SYSTEM, "SYSTEM", text);
        manager.trackMessageForPlayer(viewer, firstCopy);
        long second = manager.storeMessage(sender, "Player", text);
        manager.registerLogicalMessage(second, text);
        var secondCopy = manager.storeMessageObject(SYSTEM, "SYSTEM", text);
        manager.trackMessageForPlayer(viewer, secondCopy);
        assertTrue(manager.deleteMessage(first));
        assertTrue(firstCopy.isDeleted());
        assertFalse(secondCopy.isDeleted());
        assertEquals(second, manager.getPlayerChatHistory(viewer).getFirst().getMessageId());
    }

    @Test void identitySurvivesSerializationCompactionAndAGlyphRewrite() {
        ChatMessageManager manager = new ChatMessageManager();
        Component original = Component.text(":owner: Player: hello");
        long id = manager.storeMessage(UUID.randomUUID(), "Player", original);
        manager.registerLogicalMessage(id, original);
        Component rewritten = Component.text("\ue001 Player: hello", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.suggestCommand("/msg Player "));
        Component tagged = manager.withMessageId(id, rewritten);
        var gson = GsonComponentSerializer.gson();
        Component wire = gson.deserialize(gson.serialize(tagged.compact()));
        assertEquals("\ue001 Player: hello", PlainTextComponentSerializer.plainText().serialize(wire));
        assertEquals(rewritten.clickEvent(), wire.clickEvent());
        assertNull(wire.insertion(), "Visible text must not inherit the internal identity");
        assertEquals(id, manager.storeMessageObject(SYSTEM, "SYSTEM", wire).getMessageId());
    }

    @Test void sharedDeletionFlagReachesAnInFlightCopyNotYetInsertedIntoHistory() {
        ChatMessageManager manager = new ChatMessageManager();
        UUID viewer = UUID.randomUUID();
        Component line = Component.text("Player: message");
        long id = manager.storeMessage(UUID.randomUUID(), "Player", line);
        var inFlight = manager.storeMessageObject(SYSTEM, "SYSTEM", manager.withMessageId(id, line));
        manager.deleteMessage(id);
        manager.trackMessageForPlayer(viewer, inFlight);
        assertTrue(inFlight.isDeleted());
        assertTrue(manager.getPlayerChatHistory(viewer).isEmpty());
    }

    @Test void deletingAPlayerLineDoesNotRemoveAnUnrelatedNotificationContainingIt() {
        ChatMessageManager manager = new ChatMessageManager();
        UUID viewer = UUID.randomUUID();
        long id = manager.storeMessage(UUID.randomUUID(), "Player", Component.text("Player: hello"));
        var notification = manager.storeMessageObject(SYSTEM, "SYSTEM", Component.text("Mail quote: Player: hello"));
        manager.trackMessageForPlayer(viewer, notification);
        manager.deleteMessage(id);
        assertFalse(notification.isDeleted());
    }
}
