package codes.castled.allium.item.delivery;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Fired on the server thread immediately before Allium inserts items into a player's inventory.
 * Cancelling it gives and drops nothing; the delivery reports {@code CANCELLED_BY_EVENT}.
 */
public final class ItemGiveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender source;
    private final Player target;
    private final List<ItemStack> items;
    private final boolean equipRequested;
    private boolean cancelled;

    public ItemGiveEvent(CommandSender source, Player target, List<ItemStack> items, boolean equipRequested) {
        this.source = source;
        this.target = target;
        this.items = List.copyOf(items);
        this.equipRequested = equipRequested;
    }

    public CommandSender getSource() {
        return source;
    }

    public Player getTarget() {
        return target;
    }

    /** The stacks about to be given (copies; changing them does not change the delivery). */
    public List<ItemStack> getItems() {
        return items.stream().map(ItemStack::clone).toList();
    }

    public boolean isEquipRequested() {
        return equipRequested;
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
