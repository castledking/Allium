package su.nightexpress.quests.api.event.quest;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Minimal contract fixture for the existing patched ExcellentQuests hook. */
public final class QuestCompleteEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String id;
    public QuestCompleteEvent(Player player, String id) { super(player); this.id = id; }
    public String getQuestId() { return id; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
