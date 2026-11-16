package com.loohp.interactivechat.api.events;
import net.kyori.adventure.text.Component;
import org.bukkit.event.*;
/** Test ABI shape for the three optional public methods Allium reflects. Not a plugin runtime. */
public final class PreChatPacketSendEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS=new HandlerList();
    private final Component component;private boolean cancelled,original=true;
    public PreChatPacketSendEvent(Component component){this.component=component;}
    public Component getComponent(){return component;}
    public void setSendOriginalIfCancelled(boolean value){original=value;}
    public boolean sendOriginalIfCancelled(){return original;}
    public void setCancelled(boolean value){cancelled=value;}
    public boolean isCancelled(){return cancelled;}
    public HandlerList getHandlers(){return HANDLERS;}
    public static HandlerList getHandlerList(){return HANDLERS;}
}
