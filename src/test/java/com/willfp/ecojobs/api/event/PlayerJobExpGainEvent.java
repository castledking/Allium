package com.willfp.ecojobs.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Minimal EcoJobs public event fixture, with its mutable amount and cancellation. */
public final class PlayerJobExpGainEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final double amount;
    private boolean cancelled;
    public PlayerJobExpGainEvent(Player player, double amount) { super(player); this.amount = amount; }
    public double getAmount() { return amount; }
    public Job getJob() { return new Job(); }
    public static class Job { public String getId() { return "fisherman"; } }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean value) { cancelled = value; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
