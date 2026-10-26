package dev.nekomadev.liteFish.api;

import dev.nekomadev.litecore.managers.LiteEvent;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Minimal reflection contract fixture, not a LiteFish implementation. */
public class CatchEvent extends LiteEvent {
    private final Player player;
    private final List<ItemStack> drop;
    private final String reason;
    public CatchEvent(Player player, List<ItemStack> drop, String reason) {
        this.player = player; this.drop = drop; this.reason = reason;
    }
    public Player getPlayer() { return player; }
    public List<ItemStack> getDrop() { return drop; }
    public String getReason() { return reason; }
}
