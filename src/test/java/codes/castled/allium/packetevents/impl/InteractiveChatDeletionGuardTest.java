package codes.castled.allium.packetevents.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.chat.ChatMessageManager;
import codes.castled.allium.util.SchedulerAdapter;
import com.loohp.interactivechat.api.events.PreChatPacketSendEvent;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.event.*;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InteractiveChatDeletionGuardTest {
    @Test void delayedInteractiveChatDeliveryCannotReintroduceDeletedChatOrItsOriginalFallback() throws Exception {
        var plugin = mock(PluginStart.class);
        var server = mock(Server.class);
        var plugins = mock(PluginManager.class);
        var interactive = mock(JavaPlugin.class);
        when(interactive.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        when(plugins.getPlugin("InteractiveChat")).thenReturn(interactive);
        var manager = new ChatMessageManager();
        Component text = Component.text("message to delete");
        long id = manager.storeMessage(UUID.randomUUID(), "Player", text);
        Component sent = manager.withMessageId(id, text);
        try (var scheduler = mockStatic(SchedulerAdapter.class)) {
            var tracker = new PacketChatTrackerImpl(plugin, manager);
            var register = PacketChatTrackerImpl.class.getDeclaredMethod("registerInteractiveChatGuard");
            register.setAccessible(true);
            register.invoke(tracker);
            var executor = ArgumentCaptor.forClass(EventExecutor.class);
            verify(plugins).registerEvent(eq(PreChatPacketSendEvent.class), any(Listener.class),
                eq(EventPriority.HIGHEST), executor.capture(), eq(plugin), eq(false));
            var active = new PreChatPacketSendEvent(sent);
            executor.getValue().execute(null, active);
            assertFalse(active.isCancelled());
            manager.deleteMessage(id);
            var delayed = new PreChatPacketSendEvent(sent.compact());
            executor.getValue().execute(null, delayed);
            assertTrue(delayed.isCancelled());
            assertFalse(delayed.sendOriginalIfCancelled());
            var fallback = new PreChatPacketSendEvent(sent);
            fallback.setCancelled(true); // IC can start canceled for an oversized result.
            executor.getValue().execute(null, fallback);
            assertFalse(fallback.sendOriginalIfCancelled());
        }
    }
}
