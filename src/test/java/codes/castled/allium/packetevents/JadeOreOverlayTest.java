package codes.castled.allium.packetevents;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;

class JadeOreOverlayTest {
    private static PacketEventsAPI<?> previousApi;
    @BeforeAll static void initializeMetadataMappings() {
        previousApi = PacketEvents.getAPI();
        PacketEvents.setAPI(new PacketEventsAPI<>() {
            @Override public void load() {}
            @Override public boolean isLoaded() { return true; }
            @Override public void init() {}
            @Override public boolean isInitialized() { return true; }
            @Override public void terminate() {}
            @Override public boolean isTerminated() { return false; }
            @Override public Object getPlugin() { return null; }
            @Override public ServerManager getServerManager() { return ServerVersion::getLatest; }
            @Override public ProtocolManager getProtocolManager() { return null; }
            @Override public PlayerManager getPlayerManager() { return null; }
            @Override public NettyManager getNettyManager() { return null; }
            @Override public ChannelInjector getInjector() { return null; }
        });
    }
    @AfterAll static void restoreApi() { PacketEvents.setAPI(previousApi); }
    @Test void onlyAcceptsTheVerifiedMinecraftStringProtocol() {
        assertTrue(JadeOreOverlay.supportedHandshake(new byte[]{1,'9'}));
        for (byte[] bad : new byte[][]{{},{1},{1,'8'},{2,'9'},{1,'9',0},{-127,0,'9'}})
            assertFalse(JadeOreOverlay.supportedHandshake(bad));
    }

    @Test void markerGeometryNeverWritesAFloatIntoTheGlowColorField() {
        // This is the exact metadata list sent when the player looks at ore.
        // 26.1.2 disconnects on a Float in slot 22: that slot stores an Integer.
        var metadata = JadeOreOverlay.displayMetadata(null);
        assertTrue(metadata.stream().noneMatch(value -> value.getIndex() == 22 && value.getValue() instanceof Float),
            "field 22 is the integer glow-color override, not the display height");
        assertEquals(1f, metadata.stream().filter(value -> value.getIndex() == 20).findFirst().orElseThrow().getValue());
        assertEquals(1f, metadata.stream().filter(value -> value.getIndex() == 21).findFirst().orElseThrow().getValue());
    }
}
