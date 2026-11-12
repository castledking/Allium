package codes.castled.allium.tool.felltree;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import java.util.Objects;

/**
 * Fired once per fell, after the player broke the first log of a natural tree and before Allium
 * breaks any other log. Cancelling it leaves the tree standing (the first log stays broken).
 *
 * <p>The logs broken by the fell are never a new fell: Allium's re-entry guard ignores the break
 * events it causes. In {@code mode: direct} this is the only event other plugins see for the
 * tree's other logs.
 */
public final class LazyAxeFellEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Block firstLog;
    private final String toolId;
    private final int logs;
    private final boolean breakAsPlayer;
    private boolean cancelled;

    public LazyAxeFellEvent(Player player, Block firstLog, String toolId, int logs, boolean breakAsPlayer) {
        super(player);
        this.firstLog = Objects.requireNonNull(firstLog, "firstLog");
        this.toolId = Objects.requireNonNull(toolId, "toolId");
        this.logs = logs;
        this.breakAsPlayer = breakAsPlayer;
    }

    /** The log the player broke. */
    public Block getFirstLog() {
        return firstLog;
    }

    /** The tool definition, e.g. {@code allium:lazy_axe}. */
    public String getToolId() {
        return toolId;
    }

    /** How many more logs the fell will try to break, after the first. */
    public int getLogs() {
        return logs;
    }

    /** True for {@code mode: break-as-player}: each log then fires its own BlockBreakEvent. */
    public boolean isBreakAsPlayer() {
        return breakAsPlayer;
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
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
