package codes.castled.allium.protection;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Generic, cancellable test event fired before an Allium tool acts on an entity.
 *
 * <p>This is a question, not an action: nothing has happened yet, and cancelling it makes Allium
 * skip the action without consuming anything. Protection plugins and addons without a dedicated
 * Allium hook can cancel it. Listeners must not modify the player, target or held item.
 */
public final class AlliumProtectionCheckEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Entity target;
    private final String action;
    @Nullable
    private final Event cause;
    private boolean cancelled;

    public AlliumProtectionCheckEvent(Player player, Entity target, String action, @Nullable Event cause) {
        this.player = player;
        this.target = target;
        this.action = action;
        this.cause = cause;
    }

    public Player getPlayer() {
        return player;
    }

    public Entity getTarget() {
        return target;
    }

    /** Behavior performing the action, e.g. {@code allium:mob_disarmer}. */
    public String getAction() {
        return action;
    }

    /** The event that triggered the action, when there is one. */
    @Nullable
    public Event getCause() {
        return cause;
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
