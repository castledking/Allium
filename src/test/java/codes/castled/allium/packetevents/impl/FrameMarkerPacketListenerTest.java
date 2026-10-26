package codes.castled.allium.packetevents.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemProfile;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The framed copy of a player head must not leave the client anything to
 * resolve or compose its name line from — the owner resolves and composes
 * {@code "…'s Head"} from the profile's id too, not only from its name — so the
 * copy's profile keeps exactly the textures and nothing more.
 */
class FrameMarkerPacketListenerTest {

    private static PacketEventsAPI<?> previousApi;

    /** The component registry reads the server version, which here is only
     * ever asked of a PacketEvents API the plugin has not initialised. */
    @BeforeAll
    static void initializeMetadataMappings() {
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

    @AfterAll
    static void restoreApi() { PacketEvents.setAPI(previousApi); }

    private static final ItemProfile.Property TEXTURE =
        new ItemProfile.Property("textures", "cGFja2FnZQ==", null);

    @Test
    void textureOnlyNamedProfilesLoseTheNameThatComposesTheHeadsLine() {
        ItemProfile profile = new ItemProfile("", null, List.of(TEXTURE));

        ItemProfile stripped = FrameMarkerPacketListener.skinOnlyProfile(profile);

        assertNull(stripped.getName());
        assertEquals(TEXTURE, stripped.getProperties().get(0));
    }

    @Test
    void idOnlyProfilesLoseTheIdTheOwnerResolvesFrom() {
        ItemProfile profile = new ItemProfile(null, UUID.randomUUID(), List.of(TEXTURE));

        ItemProfile stripped = FrameMarkerPacketListener.skinOnlyProfile(profile);

        assertNull(stripped.getId());
        assertEquals(TEXTURE, stripped.getProperties().get(0));
    }

    @Test
    void namedProfilesWithBakedTexturesKeepTheirSkinButLoseTheirIdentity() {
        UUID id = UUID.nameUUIDFromBytes("allium:carrier".getBytes());
        ItemProfile profile = new ItemProfile("Notch", id, List.of(TEXTURE));

        ItemProfile stripped = FrameMarkerPacketListener.skinOnlyProfile(profile);

        assertNull(stripped.getName());
        assertNull(stripped.getId());
        assertEquals(TEXTURE, stripped.getProperties().get(0));
    }

    @Test
    void aSkinPatchCountsAsTheSkinAndSurvives() {
        com.github.retrooper.packetevents.resources.ResourceLocation cape =
            new com.github.retrooper.packetevents.resources.ResourceLocation("textures", "example_caper");
        ItemProfile profile = new ItemProfile("Notch", UUID.randomUUID(), List.of(),
            new ItemProfile.SkinPatch(null, cape, null, null));

        ItemProfile stripped = FrameMarkerPacketListener.skinOnlyProfile(profile);

        assertNull(stripped.getName());
        assertNull(stripped.getId());
        assertTrue(stripped.getProperties().isEmpty());
        assertEquals(cape, stripped.getSkinPatch().getCape());
    }
}
