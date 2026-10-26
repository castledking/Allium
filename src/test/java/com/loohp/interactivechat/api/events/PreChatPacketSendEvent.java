package com.loohp.interactivechat.api.events;

import net.kyori.adventure.text.Component;
import org.bukkit.event.*;

/** Test API surface of the optional InteractiveChat event, never shipped. */
public final class PreChatPacketSendEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Component component;
    private boolean cancelled;
    private boolean original = true;
    public PreChatPacketSendEvent(Component component) { super(true); this.component = component; }
    public Component getComponent() { return component; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public void setSendOriginalIfCancelled(boolean original) { this.original = original; }
    public boolean sendOriginalIfCancelled() { return original; }
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
