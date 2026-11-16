package codes.castled.allium.chat;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatPacketMirrorTest {
    @Test void foreignPacketIsCapturedPerViewerWithUnknownFields()throws Exception{run(false,false);}
    @Test void delayedDeletedPacketIsCancelledWithoutNewIdentity()throws Exception{run(true,false);}
    @Test void disabledModuleDoesNotMirrorNativePackets(){
        var api=mock(PacketEventsAPI.class);var events=mock(EventManager.class);when(api.getEventManager()).thenReturn(events);var service=mock(ChatService.class);var event=mock(PacketSendEvent.class);
        try(var global=mockStatic(PacketEvents.class)){global.when(PacketEvents::getAPI).thenReturn(api);try(var mirror=new ChatPacketMirror(service)){mirror.onPacketSend(event);verifyNoInteractions(event);}}
    }
    @Test void replayPacketIsNotRecaptured()throws Exception{run(false,true);}
    @SuppressWarnings({"unchecked","rawtypes"}) private void run(boolean deleted,boolean replay)throws Exception{
        var api=mock(PacketEventsAPI.class);var events=mock(EventManager.class);when(api.getEventManager()).thenReturn(events);
        var service=mock(ChatService.class);when(service.ownsChat()).thenReturn(true);var history=new ChatHistory(20,20,60000);when(service.history()).thenReturn(history);
        var player=mock(Player.class);UUID viewer=UUID.randomUUID();when(player.getUniqueId()).thenReturn(viewer);
        var event=mock(PacketSendEvent.class);when(event.getPacketType()).thenReturn(PacketType.Play.Server.SYSTEM_CHAT_MESSAGE);when(event.getPlayer()).thenReturn(player);
        String base="{\"text\":\"foreign\",\"unknown\":17}";String json=deleted?ChatIdentity.tag(base,ChatIdentity.ID+"old:1"):replay?ChatIdentity.tag(base,ChatIdentity.REPLAY):base;
        when(service.deletedIdentity("old:1")).thenReturn(true);
        try(var global=mockStatic(PacketEvents.class);var wrappers=mockConstruction(WrapperPlayServerSystemChatMessage.class,(packet,context)->when(packet.getMessageJson()).thenReturn(json))){
            global.when(PacketEvents::getAPI).thenReturn(api);
            try(var mirror=new ChatPacketMirror(service)){mirror.onPacketSend(event);var packet=wrappers.constructed().get(0);
                if(deleted){verify(event).setCancelled(true);assertTrue(history.visible(viewer,System.currentTimeMillis()).isEmpty());}
                else if(replay){verify(packet,never()).setMessageJson(anyString());assertTrue(history.visible(viewer,System.currentTimeMillis()).isEmpty());}
                else {assertEquals(1,history.visible(viewer,System.currentTimeMillis()).size());String retained=history.visible(viewer,System.currentTimeMillis()).get(0).rendered().get(viewer);assertTrue(retained.contains("unknown"));assertNotNull(ChatIdentity.metadata(retained).id());verify(event).markForReEncode(true);}
            }
        }
    }
}
