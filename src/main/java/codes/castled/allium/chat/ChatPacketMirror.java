package codes.castled.allium.chat;
import codes.castled.allium.PluginStart;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import java.util.*;
import org.bukkit.entity.Player;

/** Optional system-chat mirror. No inferred sender or text matching for foreign packets. */
public final class ChatPacketMirror extends PacketListenerAbstract implements AutoCloseable {
    private final ChatService service;
    public ChatPacketMirror(ChatService service){super(PacketListenerPriority.HIGHEST);this.service=service;PacketEvents.getAPI().getEventManager().registerListener(this);}
    @Override public void onPacketSend(PacketSendEvent event){
        if(!service.ownsChat()||event.isCancelled()||event.getPacketType()!=PacketType.Play.Server.SYSTEM_CHAT_MESSAGE||!(event.getPlayer() instanceof Player player))return;
        var packet=new WrapperPlayServerSystemChatMessage(event);if(packet.isOverlay())return;
        String json=packet.getMessageJson();var metadata=ChatIdentity.metadata(json);
        if(metadata.id()!=null){if(service.deletedIdentity(metadata.id()))event.setCancelled(true);return;}
        if(metadata.replay()||json==null||json.length()>262144)return;
        String plain=net.md_5.bungee.api.chat.TextComponent.toPlainText(net.md_5.bungee.chat.ComponentSerializer.parse(json));
        if(plain.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)))return;
        String id=service.history().nextId();String marked=ChatIdentity.tag(json,ChatIdentity.ID+id);
        service.history().add(id,new UUID(0,0),"external","external",System.currentTimeMillis(),Map.of(player.getUniqueId(),marked));packet.setMessageJson(marked);event.markForReEncode(true);
    }
    @Override public void close(){PacketEvents.getAPI().getEventManager().unregisterListener(this);}
}
