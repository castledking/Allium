package net.survivalfun.core.items.impl;

import net.survivalfun.core.items.CustomItem;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spawner Type Changer tool. Matches Essentials reference.
 * Single-use; opens intake menu on right-click spawner; rate-limited message on right-click air.
 */
public class SpawnerChangerItem extends CustomItem {

    public static final String ITEM_ID = "spawner_changer";
    private static final long MESSAGE_COOLDOWN_MS = 10000; // 10 seconds

    private final SpawnerChangerManager manager;
    private final Map<UUID, Long> lastAirClickMessage = new HashMap<>();

    public SpawnerChangerItem(Plugin plugin, SpawnerChangerManager manager) {
        super(plugin, ITEM_ID);
        this.manager = manager;
    }

    public SpawnerChangerItem(Plugin plugin) {
        this(plugin, null);
    }

    @Override
    public Material getMaterial() {
        return Material.BLAZE_ROD;
    }

    @Override
    public String getDisplayName() {
        return ChatColor.GOLD + "" + ChatColor.BOLD + "Spawner Type Changer";
    }

    @Override
    public List<String> getLore() {
        return Arrays.asList(
            ChatColor.GRAY + "Right-Click on any spawner",
            ChatColor.GRAY + "with this tool to open",
            ChatColor.GRAY + "the changer menu.",
            "",
            ChatColor.RED + "Warning: This item only has one use!"
        );
    }

    @Override
    public String getTextureType() {
        return "item_model";
    }

    @Override
    public Object getItemModel() {
        return "oraxen:spawner_changer";
    }

    @Override
    public int getCustomModelData() {
        return 1002;
    }

    @Override
    public boolean isSingleUse() {
        return true;
    }

    @Override
    public boolean onUseOnBlock(Player player, ItemStack item, Block block, BlockFace face) {
        if (block.getType() != Material.SPAWNER || manager == null) return false;
        manager.openIntakeMenu(player, block.getLocation());
        return true;
    }

    @Override
    public boolean onUse(Player player, ItemStack item) {
        // Rate-limited message when right-clicking air (matches Essentials reference)
        long now = System.currentTimeMillis();
        Long lastTime = lastAirClickMessage.get(player.getUniqueId());
        if (lastTime == null || (now - lastTime) >= MESSAGE_COOLDOWN_MS) {
            player.sendMessage(ChatColor.YELLOW + "Right-click on a spawner to change its type!");
            lastAirClickMessage.put(player.getUniqueId(), now);
        }
        return true;
    }
}
