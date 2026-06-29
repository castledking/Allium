package codes.castled.allium.packetevents;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Sends player data to CrowBar client mod via custom payload packets.
 * This allows CrowBar to show players beyond the 144-block tracking limit
 * and bypass server-side waypoint filtering (sneaking, pumpkin, vanish).
 */
public class CrowBarDataSender implements Listener {
    private static final Gson GSON = new Gson();
    private static final String CHANNEL = "crowbar:player_data";
    private static final int TICK_INTERVAL = 1; // Send every tick (50ms)
    
    // Golden angle (≈137.508°) — ensures maximally separated hues for any number of players.
    // Colors are generated in HSV with high saturation & brightness so they never look muddy.
    private static final float GOLDEN_ANGLE = 137.508f;
    
    private final JavaPlugin plugin;
    private SchedulerAdapter.TaskHandle taskHandle = null;

    public CrowBarDataSender(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (taskHandle != null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        taskHandle = SchedulerAdapter.runTimer(() -> sendPlayerData(), 20L, TICK_INTERVAL);
    }

    public void stop() {
        if (taskHandle != null) {
            taskHandle.cancel();
            taskHandle = null;
        }
        HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sendPlayerData(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        sendDelayedSnapshots(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        sendDelayedSnapshots(event.getPlayer());
    }

    private void sendDelayedSnapshots(Player recipient) {
        sendDelayedSnapshot(recipient.getUniqueId(), 1L);
        sendDelayedSnapshot(recipient.getUniqueId(), 5L);
        sendDelayedSnapshot(recipient.getUniqueId(), 20L);
    }

    private void sendDelayedSnapshot(UUID recipientUuid, long delayTicks) {
        SchedulerAdapter.runLater(() -> {
            Player recipient = Bukkit.getPlayer(recipientUuid);
            if (recipient != null && recipient.isOnline()) {
                sendPlayerDataTo(recipient, null);
            }
        }, delayTicks);
    }

    private void sendPlayerData() {
        sendPlayerData(null);
    }

    private void sendPlayerData(UUID excludedUuid) {
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (excludedUuid != null && recipient.getUniqueId().equals(excludedUuid)) {
                continue;
            }
            sendPlayerDataTo(recipient, excludedUuid);
        }
    }

    private void sendPlayerDataTo(Player recipient, UUID excludedUuid) {
        // Only send to players with the allium.crowbar permission
        if (!recipient.hasPermission("allium.crowbar")) {
            return;
        }

        JsonArray playersArray = new JsonArray();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (excludedUuid != null && player.getUniqueId().equals(excludedUuid)) {
                continue;
            }
            if (!player.getWorld().equals(recipient.getWorld())) {
                continue;
            }
            if (PluginStart.getInstance().isCitizensNpc(player)) {
                continue;
            }

            playersArray.add(createPlayerObject(player));
        }

        JsonObject root = new JsonObject();
        root.add("players", playersArray);
        byte[] data = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);

        try {
            recipient.sendPluginMessage(plugin, CHANNEL, data);
        } catch (Exception e) {
            // Silent fail - packet send errors are not critical
        }
    }

    private JsonObject createPlayerObject(Player player) {
        JsonObject playerObj = new JsonObject();
        playerObj.addProperty("uuid", player.getUniqueId().toString());
        playerObj.addProperty("x", player.getLocation().getX());
        playerObj.addProperty("y", player.getLocation().getY());
        playerObj.addProperty("z", player.getLocation().getZ());
        playerObj.addProperty("wearingPumpkin", isWearingCarvedPumpkin(player));
        playerObj.addProperty("sneaking", player.isSneaking());
        playerObj.addProperty("vanished", isVanished(player));
        playerObj.addProperty("teamColor", getTeamColor(player));
        return playerObj;
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
        String playerName = player.getName().toLowerCase();

        // Check for per-player color override in config
        String overrideColor = plugin.getConfig().getString("player-colors." + playerName);
        if (overrideColor != null && !overrideColor.isEmpty()) {
            try {
                String hex = overrideColor.startsWith("#") ? overrideColor.substring(1) : overrideColor;
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                // Invalid hex color, fall through
            }
        }

        return getMojangishGeneratedColor(player.getUniqueId());
    }

    private static int getMojangishGeneratedColor(UUID uuid) {
        long hash = uuid.hashCode() & 0xffffffffL;
        float hue = (hash * GOLDEN_ANGLE) % 360f;
        float saturation = 0.75f + ((hash >> 8) & 0xFF) / 255f * 0.20f;
        float brightness = 0.85f + ((hash >> 16) & 0xFF) / 255f * 0.15f;
        return java.awt.Color.HSBtoRGB(hue / 360f, saturation, brightness) & 0xFFFFFF;
    }
}
