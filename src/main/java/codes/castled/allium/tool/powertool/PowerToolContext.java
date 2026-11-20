package codes.castled.allium.tool.powertool;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime execution context for one powertool activation.
 */
public record PowerToolContext(
        Player player,
        String toolId,
        @Nullable String instanceUuid,
        ItemStack item,
        String hand,
        PowerToolTrigger trigger,
        @Nullable Block block,
        @Nullable Entity targetEntity,
        @Nullable Event causeEvent
) {
    /** Builds the safe placeholder map for command, message and actionbar substitution. */
    public Map<String, String> placeholders() {
        Map<String, String> map = new LinkedHashMap<>();
        Location loc = player.getLocation();
        map.put("player", player.getName());
        map.put("uuid", player.getUniqueId().toString());
        map.put("world", loc.getWorld() == null ? "" : loc.getWorld().getName());
        map.put("x", Integer.toString(loc.getBlockX()));
        map.put("y", Integer.toString(loc.getBlockY()));
        map.put("z", Integer.toString(loc.getBlockZ()));
        map.put("yaw", Integer.toString(Math.round(loc.getYaw())));
        map.put("pitch", Integer.toString(Math.round(loc.getPitch())));
        map.put("hand", hand);
        map.put("item", item.getType().getKey().toString());
        if (targetEntity != null) {
            map.put("target", targetEntity.getName());
            map.put("target_uuid", targetEntity.getUniqueId().toString());
        }
        if (block != null) {
            map.put("block_x", Integer.toString(block.getX()));
            map.put("block_y", Integer.toString(block.getY()));
            map.put("block_z", Integer.toString(block.getZ()));
            map.put("block_type", block.getType().name().toLowerCase(Locale.ROOT));
        }
        return map;
    }
}
