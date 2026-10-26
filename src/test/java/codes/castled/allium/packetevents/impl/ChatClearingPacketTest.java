package codes.castled.allium.packetevents.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.chat.ChatMessageManager;
import codes.castled.allium.managers.chat.ChatHistoryClear;
import codes.castled.allium.util.SchedulerAdapter;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ChatClearingPacketTest {
    @Test void clearingUsesRealNonOverlayPacketsAndBypassesChatFormattingFilters() {
        var plugin = mock(PluginStart.class);
        var config = new YamlConfiguration();
        config.set("chat.deletion_resend.clear_lines", 100);
        config.set("chat.deletion_resend.header_enabled", false);
        when(plugin.getConfig()).thenReturn(config);
        var viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        var api = mock(PacketEventsAPI.class);
        var players = mock(PlayerManager.class);
        var server = mock(ServerManager.class);
        when(api.getPlayerManager()).thenReturn(players);
        when(api.getServerManager()).thenReturn(server);
        when(api.getSettings()).thenReturn(new com.github.retrooper.packetevents.settings.PacketEventsSettings());
        when(server.getVersion()).thenReturn(ServerVersion.getLatest());
        List<WrapperPlayServerSystemChatMessage> packets = new ArrayList<>();
        doAnswer(i -> { packets.add((WrapperPlayServerSystemChatMessage)i.getArgument(1)); return null; })
            .when(players).sendPacketSilently(eq(viewer), any(PacketWrapper.class));
        try (var pe = mockStatic(PacketEvents.class);
             var scheduler = mockStatic(SchedulerAdapter.class)) {
            pe.when(PacketEvents::getAPI).thenReturn(api);
            new PacketChatTrackerImpl(plugin, new ChatMessageManager()).resendChatHistoryToPlayer(viewer);
        }
        assertEquals(100, packets.size());
        assertTrue(packets.stream().allMatch(p -> !p.isOverlay() && ChatHistoryClear.isWhitespace(p.getMessage())));
        verify(viewer, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
}
