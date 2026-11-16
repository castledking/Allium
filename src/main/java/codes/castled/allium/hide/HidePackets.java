package codes.castled.allium.hide;

import codes.castled.allium.PluginStart;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareCommands;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import org.bukkit.entity.Player;
import java.util.*;

/** Packet metadata filtering uses immutable snapshots; no Bukkit permissions or disk reads on Netty. */
final class HidePackets extends PacketListenerAbstract implements HidePacketHooks {
    private final HideService service;
    private final PluginStart plugin;
    private final SuggestionRequests requests=new SuggestionRequests();
    private long lastWarning;
    HidePackets(HideService service,PluginStart plugin){super(PacketListenerPriority.HIGHEST);this.service=service;this.plugin=plugin;PacketEvents.getAPI().getEventManager().registerListener(this);}
    @Override public void onPacketReceive(PacketReceiveEvent event){
        if(event.isCancelled()||event.getPacketType()!=PacketType.Play.Client.TAB_COMPLETE||!service.settings().enabled())return;
        if(!(event.getPlayer() instanceof Player player))return;var view=service.viewer(player.getUniqueId());
        var packet=new WrapperPlayClientTabComplete(event);Integer id=packet.getTransactionId().orElse(null);if(id==null)return;
        String text=Objects.toString(packet.getText(),"");
        if(view==null){event.setCancelled(true);return;}
        if(!view.bypass()&&view.settings().filterSuggestions()&&view.settings().cancelBareSlash()&&text.trim().equals("/")){event.setCancelled(true);return;}
        if(!requests.add(player.getUniqueId(),id,text,System.currentTimeMillis()))event.setCancelled(true);
    }
    @Override public void onPacketSend(PacketSendEvent event){
        if(event.isCancelled()||!service.settings().enabled()||!(event.getPlayer() instanceof Player player))return;
        var view=service.viewer(player.getUniqueId());
        if(view==null){if(event.getPacketType()==PacketType.Play.Server.DECLARE_COMMANDS||event.getPacketType()==PacketType.Play.Server.TAB_COMPLETE)event.setCancelled(true);return;}
        try{
            if(event.getPacketType()==PacketType.Play.Server.DECLARE_COMMANDS){
                var packet=new WrapperPlayServerDeclareCommands(event);var tree=CommandTreeFilter.filter(packet.getNodes(),packet.getRootIndex(),label->HideService.packetVisible(view,label),service.scopedRoots(view));
                packet.setNodes(tree.nodes());packet.setRootIndex(tree.root());event.markForReEncode(true);
            }else if(event.getPacketType()==PacketType.Play.Server.TAB_COMPLETE){
                var packet=new WrapperPlayServerTabComplete(event);Integer id=packet.getTransactionId().orElse(null);var request=id==null?null:requests.take(player.getUniqueId(),id,System.currentTimeMillis());
                if(request!=null&&request.poisoned()){event.setCancelled(true);return;}
                if(request==null){if(!view.bypass()&&view.settings().filterSuggestions()&&view.settings().cancelUnknown())event.setCancelled(true);return;}
                if(!request.input().startsWith("/"))return;
                int start=packet.getCommandRange().map(WrapperPlayServerTabComplete.CommandRange::getBegin).orElse(-1);var matches=packet.getCommandMatches();if(matches==null)return;
                var kept=matches.stream().filter(match->HideService.packetVisible(view,HidePolicy.suggestionPath(request.input(),match.getText(),start))).toList();
                if(kept.isEmpty()&&!view.bypass()){event.setCancelled(true);return;}
                packet.setCommandMatches(kept);event.markForReEncode(true);
            }
        }catch(RuntimeException|LinkageError malformed){
            event.setCancelled(true);long now=System.currentTimeMillis();synchronized(this){if(now-lastWarning>60_000){lastWarning=now;plugin.logger().warning("Hide command metadata refused: "+malformed.getClass().getSimpleName());}}
        }
    }
    @Override public void forget(UUID player){requests.forget(player);}
    @Override public void close(){requests.clear();try{PacketEvents.getAPI().getEventManager().unregisterListener(this);}catch(RuntimeException|LinkageError ignored){}}
}
