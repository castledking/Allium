package codes.castled.allium.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Fired when another plugin requests player name completions for a command.
 * Allium's listener populates the list with all online non-vanished players
 * when PartyManager is enabled. If {@link #getCompletions()} returns null
 * after the event fires, the requesting plugin should fall back to its
 * default completion logic (e.g. Bukkit's canSee).
 */
public class AlliumTabCompletionsEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player viewer;
    private List<String> completions;

    public AlliumTabCompletionsEvent(@NotNull Player viewer) {
        this.viewer = viewer;
    }

    /**
     * The player who is tab-completing a command.
     */
    public @NotNull Player getViewer() {
        return viewer;
    }

    /**
     * Returns the list of player names that should appear as completions,
     * or null if Allium/PartyManager is not handling this.
     */
    public @Nullable List<String> getCompletions() {
        return completions;
    }

    /**
     * Set the list of player names that should appear as completions.
     * Should be set to a non-null list when Allium's PartyManager is active.
     */
    public void setCompletions(@Nullable List<String> completions) {
        this.completions = completions;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
