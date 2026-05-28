package codes.castled.allium.packetevents;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import codes.castled.allium.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Sends player data to CrowBar client mod via custom payload packets.
 * This allows CrowBar to show players beyond the 144-block tracking limit
 * and bypass server-side waypoint filtering (sneaking, pumpkin, vanish).
 */
public class CrowBarDataSender {
    private static final Gson GSON = new Gson();
    private static final String CHANNEL = "crowbar:player_data";
    private static final int TICK_INTERVAL = 5; // Send every 5 ticks (250ms)
    
    private final JavaPlugin plugin;
    private int taskId = -1;

    public CrowBarDataSender(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (taskId != -1) {
            return; // Already running
        }

        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::sendPlayerData, 20L, TICK_INTERVAL).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void sendPlayerData() {
        JsonArray playersArray = new JsonArray();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (isCitizensNpcUuid(uuid)) continue;
            JsonObject playerObj = new JsonObject();
            playerObj.addProperty("uuid", uuid.toString());
            playerObj.addProperty("x", player.getLocation().getX());
            playerObj.addProperty("y", player.getLocation().getY());
            playerObj.addProperty("z", player.getLocation().getZ());
            playerObj.addProperty("wearingPumpkin", isWearingCarvedPumpkin(player));
            playerObj.addProperty("sneaking", player.isSneaking());
            playerObj.addProperty("vanished", isVanished(player));
            playerObj.addProperty("teamColor", getTeamColor(player));
            playersArray.add(playerObj);
        }

        JsonObject root = new JsonObject();
        root.add("players", playersArray);
        String json = GSON.toJson(root);

        byte[] data = json.getBytes(StandardCharsets.UTF_8);

        for (Player recipient : Bukkit.getOnlinePlayers()) {
            // Only send to players with the allium.crowbar permission
            if (!recipient.hasPermission("allium.crowbar")) {
                continue;
            }
            try {
                recipient.sendPluginMessage(plugin, CHANNEL, data);
            } catch (Exception e) {
                // Silent fail - packet send errors are not critical
            }
        }
    }

    private boolean isCitizensNpcUuid(UUID uuid) {
        return ((uuid.getMostSignificantBits() >> 12) & 0xF) == 2;
    }

    private boolean isWearingCarvedPumpkin(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet == null) {
            return false;
        }
        String materialName = helmet.getType().name();
        return materialName.equals("CARVED_PUMPKIN") || materialName.equals("PLAYER_HEAD");
    }

    private boolean isVanished(Player player) {
        // Check with Allium's vanish manager if available
        if (PluginStart.getInstance().getVanishManager() != null) {
            return PluginStart.getInstance().getVanishManager().isVanished(player);
        }
        // Fallback: check if player is hidden from other players
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != player && !other.canSee(player)) {
                return true;
            }
        }
        return false;
    }

    private int getTeamColor(Player player) {
        org.bukkit.scoreboard.Team team = player.getScoreboard().getEntryTeam(player.getName());

        int rgb = 0xFFFFFF;
        if (team != null && team.getColor() != null && team.getColor().asBungee().getColor() != null) {
            java.awt.Color color = team.getColor().asBungee().getColor();
            rgb = (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        }
        return rgb;
    }
}
