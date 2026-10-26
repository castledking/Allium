package codes.castled.allium.commands.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.chat.ChatMessageManager;
import codes.castled.allium.packetevents.ChatPacketTracker;
import codes.castled.allium.util.SchedulerAdapter;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class DeleteMsgTest {
    @Test void aSecondDeletionDuringRebuildSchedulesAnotherPass() {
        var plugin = mock(PluginStart.class);
        var tracker = mock(ChatPacketTracker.class);
        when(plugin.getChatPacketTracker()).thenReturn(tracker);
        when(tracker.supportsResend()).thenReturn(true);
        var staff = mock(Player.class);
        when(staff.hasPermission("allium.staff")).thenReturn(true);
        when(staff.getName()).thenReturn("Moderator");
        var manager = new ChatMessageManager();
        long first = manager.storeMessage(UUID.randomUUID(), "A", Component.text("first"));
        long second = manager.storeMessage(UUID.randomUUID(), "B", Component.text("second"));
        var command = new DeleteMsg(plugin, manager);
        Deque<Runnable> scheduled = new ArrayDeque<>();
        try (var scheduler = mockStatic(SchedulerAdapter.class)) {
            scheduler.when(() -> SchedulerAdapter.runLater(any(Runnable.class), anyLong())).thenAnswer(i -> {
                scheduled.addLast(i.getArgument(0)); return null;
            });
            command.onCommand(staff, mock(Command.class), "delmsg", new String[]{Long.toString(first)});
            scheduled.removeFirst().run(); // First rebuild finishes; its guard is still held.
            command.onCommand(staff, mock(Command.class), "delmsg", new String[]{Long.toString(second)});
            scheduled.removeFirst().run(); // Release must notice the pending second delete.
            assertFalse(scheduled.isEmpty(), "Second deletion cannot be dropped in the guard window");
            scheduled.removeFirst().run();
            verify(tracker, times(2)).resendChatHistoryToAllPlayers();
            assertTrue(manager.getMessage(first).isDeleted());
            assertTrue(manager.getMessage(second).isDeleted());
        }
    }
}
