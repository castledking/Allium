package dev.nekomadev.litecore.managers;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Minimal API fixture: LiteFish event subclasses all share this handler list. */
public class LiteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
