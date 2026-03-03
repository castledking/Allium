package net.survivalfun.core.events;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when Allium gives an item to a player via /give or /i.
 * Allows other plugins (e.g. WAYC) to log the actual ItemStack received.
 */
public class ItemGiveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player target;
    private final ItemStack item;
    private final int amountGiven;
    private final CommandSender executor;
    private final String source; // "give" or "item"
    private boolean cancelled;

    public ItemGiveEvent(Player target, ItemStack item, int amountGiven, CommandSender executor, String source) {
        this.target = target;
        this.item = item != null ? item.clone() : null;
        this.amountGiven = amountGiven;
        this.executor = executor;
        this.source = source;
    }

    public Player getTarget() {
        return target;
    }

    public ItemStack getItem() {
        return item != null ? item.clone() : null;
    }

    /** Amount actually given (inventory + offhand + armor + drops). */
    public int getAmountGiven() {
        return amountGiven;
    }

    public CommandSender getExecutor() {
        return executor;
    }

    public String getSource() {
        return source;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
