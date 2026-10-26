package com.leonardobishop.quests.bukkit.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Minimal public event contract fixture for the optional Quests integration. */
public final class PlayerFinishQuestEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Progress progress;
    public PlayerFinishQuestEvent(Player player, String id, long completedAt) {
        super(player);
        progress = new Progress(id, completedAt);
    }
    public Progress getQuestProgress() { return progress; }
    public record Progress(String id, long completedAt) {
        public String getQuestId() { return id; }
        public long getCompletionDate() { return completedAt; }
    }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
