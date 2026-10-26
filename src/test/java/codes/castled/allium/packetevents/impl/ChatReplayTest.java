package codes.castled.allium.packetevents.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.chat.ChatMessageManager;
import codes.castled.allium.util.SchedulerAdapter;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import java.util.concurrent.atomic.AtomicReference;

class ChatReplayTest {
    private static final UUID SYSTEM = new UUID(0, 0);

    @Test void clearEvictsOldMessageEntriesRatherThanOnlyHidingTheirWrappedLines() {
        PluginStart plugin = mock(PluginStart.class);
        var config = new YamlConfiguration();
        config.set("chat.deletion_resend.clear_lines", 100);
        config.set("chat.deletion_resend.header_enabled", false);
        when(plugin.getConfig()).thenReturn(config);
        Player viewer = mock(Player.class);
        UUID viewerId = UUID.randomUUID();
        when(viewer.getUniqueId()).thenReturn(viewerId);
        ChatMessageManager manager = new ChatMessageManager();
        Component deleted = Component.text("remove this exact message");
        long id = manager.storeMessage(UUID.randomUUID(), "Sender", deleted);
        manager.registerLogicalMessage(id, deleted);
        manager.trackMessageForPlayer(viewerId, manager.storeMessageObject(SYSTEM, "SYSTEM", deleted));
        assertTrue(manager.deleteMessage(id));

        // The client bounds stored message entries separately from rendered
        // lines. Twenty newlines in one packet still occupy one history entry.
        Deque<String> clientEntries = new ArrayDeque<>();
        for (int i = 0; i < 99; i++) clientEntries.addLast("old " + i);
        clientEntries.addLast("remove this exact message");
        doAnswer(i -> {
            clientEntries.addLast(PlainTextComponentSerializer.plainText().serialize(i.getArgument(0, Component.class)));
            while (clientEntries.size() > 100) clientEntries.removeFirst();
            return null;
        }).when(viewer).sendMessage(any(Component.class));
        try (var scheduler = mockStatic(SchedulerAdapter.class)) {
            new PacketChatTrackerImpl(plugin, manager).resendChatHistoryToPlayer(viewer);
        }
        assertFalse(clientEntries.contains("remove this exact message"),
            "Batched newlines leave the original message in the client's stored history");
    }

    @Test void deletingTheLastVisibleMessageDoesNotReplayAnotherViewersPrivateChat() {
        PluginStart plugin = mock(PluginStart.class);
        var config = new YamlConfiguration();
        config.set("chat.deletion_resend.clear_lines", 0);
        config.set("chat.deletion_resend.header_enabled", false);
        when(plugin.getConfig()).thenReturn(config);
        Player viewer = mock(Player.class);
        UUID viewerId = UUID.randomUUID();
        when(viewer.getUniqueId()).thenReturn(viewerId);
        ChatMessageManager manager = new ChatMessageManager();
        Component secret = Component.text("private staff message");
        manager.trackMessageForPlayer(UUID.randomUUID(), manager.storeMessageObject(SYSTEM, "SYSTEM", secret));
        List<Component> sent = new ArrayList<>();
        doAnswer(i -> { sent.add(i.getArgument(0, Component.class)); return null; })
            .when(viewer).sendMessage(any(Component.class));
        try (var scheduler = mockStatic(SchedulerAdapter.class)) {
            new PacketChatTrackerImpl(plugin, manager).resendChatHistoryToPlayer(viewer);
        }
        assertTrue(sent.stream().noneMatch(c -> PlainTextComponentSerializer.plainText().serialize(c).contains("private staff message")));
    }

    @Test void intentionalRepeatedLinesStaySeparateButDuplicateCapturesOfOneIdDoNot() {
        var plugin = mock(PluginStart.class);
        var config = new YamlConfiguration();
        config.set("chat.deletion_resend.clear_lines", 0);
        config.set("chat.deletion_resend.header_enabled", false);
        when(plugin.getConfig()).thenReturn(config);
        var viewer = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(viewer.getUniqueId()).thenReturn(id);
        var manager = new ChatMessageManager();
        Component repeated = Component.text("Player: hello");
        for (int i = 0; i < 2; i++) {
            long logical = manager.storeMessage(UUID.randomUUID(), "Player", repeated);
            Component sent = manager.withMessageId(logical, repeated);
            manager.trackMessageForPlayer(id, manager.storeMessageObject(SYSTEM, "SYSTEM", sent));
            manager.trackMessageForPlayer(id, manager.storeMessageObject(SYSTEM, "SYSTEM", sent));
        }
        List<Component> sent = new ArrayList<>();
        doAnswer(i -> { sent.add(i.getArgument(0, Component.class)); return null; })
            .when(viewer).sendMessage(any(Component.class));
        try (var scheduler = mockStatic(SchedulerAdapter.class)) {
            new PacketChatTrackerImpl(plugin, manager).resendChatHistoryToPlayer(viewer);
        }
        assertEquals(2, sent.size());
        assertTrue(sent.stream().allMatch(manager::isReplay));
    }

    @Test void delayedReplayPacketsAreIgnoredButFreshChatDuringReplayIsStillCaptured() {
        var plugin = mock(PluginStart.class);
        var config = new YamlConfiguration();
        config.set("chat.deletion_resend.clear_lines", 0);
        config.set("chat.deletion_resend.header_enabled", false);
        when(plugin.getConfig()).thenReturn(config);
        var viewer = mock(Player.class);
        UUID viewerId = UUID.randomUUID();
        when(viewer.getUniqueId()).thenReturn(viewerId);
        var manager = new ChatMessageManager();
        var event = mock(PacketSendEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Server.SYSTEM_CHAT_MESSAGE);
        when(event.getPlayer()).thenReturn(viewer);
        AtomicReference<Component> onWire = new AtomicReference<>();
        try (var scheduler = mockStatic(SchedulerAdapter.class);
             var bukkit = mockStatic(org.bukkit.Bukkit.class);
             var wrappers = mockConstruction(WrapperPlayServerSystemChatMessage.class, (packet, context) ->
                 when(packet.getMessage()).thenAnswer(i -> onWire.get()))) {
            bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(List.of(viewer));
            var tracker = new PacketChatTrackerImpl(plugin, manager);
            Component old = Component.text("before deletion");
            long id = manager.storeMessage(UUID.randomUUID(), "Player", old);
            onWire.set(manager.withMessageId(id, old));
            tracker.onPacketSend(event);
            assertEquals(1, manager.getPlayerChatHistory(viewerId).size());
            doAnswer(i -> {
                onWire.set(i.getArgument(0, Component.class));
                tracker.onPacketSend(event);
                onWire.set(Component.text("arrived during the active rebuild"));
                tracker.onPacketSend(event);
                return null;
            }).when(viewer).sendMessage(any(Component.class));
            tracker.resendChatHistoryToAllPlayers();
            assertEquals(2, manager.getPlayerChatHistory(viewerId).size(),
                "Global replay guards must not discard unrelated live messages");
            onWire.set(manager.withMessageId(id, old));
            onWire.set(manager.asReplay(onWire.get()).compact());
            tracker.onPacketSend(event); // Arbitrarily late delivery; no timer assumptions.
            assertEquals(2, manager.getPlayerChatHistory(viewerId).size());
            onWire.set(Component.text("new independent system message"));
            tracker.onPacketSend(event);
            assertEquals(3, manager.getPlayerChatHistory(viewerId).size());
            manager.deleteMessage(id);
            onWire.set(manager.withMessageId(id, old));
            tracker.onPacketSend(event);
            verify(event).setCancelled(true);
            assertEquals(2, manager.getPlayerChatHistory(viewerId).size());
        }
    }
}
