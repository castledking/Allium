package codes.castled.allium.items.impl;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.items.CustomItem;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Item Renamer tool.
 * When in player's inventory, grants permissions to use /rename command.
 * Single-use item that is consumed after successful rename.
 */
public class ItemRenamerItem extends CustomItem {

    public static final String ITEM_ID = "item_renamer";
    private static final long MESSAGE_COOLDOWN_MS = 10000; // 10 seconds

    private final Map<UUID, Long> lastUseMessage = new HashMap<>();

    public ItemRenamerItem(final Plugin plugin) {
        super(plugin, ITEM_ID);
    }

    @Override
    public Material getMaterial() {
        return Material.NAME_TAG;
    }

    @Override
    public String getDisplayName() {
        return ChatColor.AQUA + "" + ChatColor.BOLD + "Item Renamer";
    }

    @Override
    public List<String> getLore() {
        return Arrays.asList(
            ChatColor.GRAY + "Hold any item and use",
            ChatColor.GRAY + "/rename <name> to rename it.",
            "",
            ChatColor.YELLOW + "Supports MiniMessage formatting!",
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
        return "oraxen:item_renamer";
    }

    @Override
    public int getCustomModelData() {
        return 1003; // Item Renamer custom model data
    }

    @Override
    public boolean isSingleUse() {
        return true;
    }

    @Override
    public boolean onUse(final org.bukkit.entity.Player player, final org.bukkit.inventory.ItemStack item) {
        // Rate limit this message
        final long now = System.currentTimeMillis();
        final Long lastTime = lastUseMessage.get(player.getUniqueId());

        if (lastTime == null || (now - lastTime) >= MESSAGE_COOLDOWN_MS) {
            player.sendMessage(ChatColor.YELLOW + "Hold the item you want to rename and use " +
                    ChatColor.GOLD + "/rename <name>" + ChatColor.YELLOW + "!");
            lastUseMessage.put(player.getUniqueId(), now);
        }
        return true;
    }
}
