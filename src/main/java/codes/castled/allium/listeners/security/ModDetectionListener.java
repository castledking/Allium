package codes.castled.allium.listeners.security;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;

import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;

import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects Fabric/Forge mods by intercepting plugin channel registration packets.
 * Kicks players with banned mods like Meteor Client, Freecam, InventoryProfiles Next, etc.
 */
public class ModDetectionListener extends PacketListenerAbstract implements Listener {

    private final PluginStart plugin;
    private final Set<String> bannedMods;
    private final Set<String> bannedChannels;
    private final Map<UUID, Set<String>> playerMods = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final String kickMessage;

    // Known mod channel prefixes/patterns
    private static final Set<String> KNOWN_MOD_CHANNELS = Set.of(
        // Fabric API channels
        "fabric:",
        "fabric-networking-api-v1",
        "fabric-events-interaction-v0",
        "fabric-screen-handler-api-v1",
        // Forge channels
        "fml:",
        "forge:",
        // Common cheat/modded client channels
        "meteor-client",
        "meteor_client",
        "meteor:",
        "meteor-noconfig", // Meteor Client no-config variant
        "freecam",
        "free_cam",
        "freecam:toggle", // Freecam toggle channel
        "wurst",
        "wurst-client",
        "wurst_client",
        "inventoryprofiles",
        "inventoryprofilesnext",
        "inventory_profiles",
        "inventory_profiles_next",
        "invprofiles",
        "ipnext",
        "libipn", // Inventory Profiles Next library
        "xaerominimap",
        "xaeroworldmap",
        "journeymap",
        "worldmap",
        "waila",
        "jade",
        "hwyla",
        "appleskin",
        "tweakeroo",
        "litematica",
        "litematica-server-paster",
        "minihud",
        "itemscroller",
        "mousewheelie",
        "roughlyenoughitems",
        "jei",
        "rei",
        "emi",
        "modmenu",
        "sodium",
        "iris",
        "lithium",
        "phosphor",
        "starlight",
        "carpet",
        "baritone",
        "wurst",
        "voxy", // Render distance mod
        "future",
        "impact",
        "inertia",
        "aristois",
        "sigma",
        "liquidbounce"
    );

    public ModDetectionListener(PluginStart plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("mod-detection.enabled", true);
        
        // Load banned mods from config
        List<String> configBanned = plugin.getConfig().getStringList("mod-detection.banned-mods");
        this.bannedMods = new HashSet<>(configBanned);
        
        // Load banned channels from config
        List<String> configChannels = plugin.getConfig().getStringList("mod-detection.banned-channels");
        this.bannedChannels = new HashSet<>(configChannels);
        
        this.kickMessage = plugin.getConfig().getString("mod-detection.kick-message", 
            "§cYou are using a prohibited mod: §e{mod}\n\n§7Please remove this mod to join the server.");
        
        if (enabled) {
            PacketEvents.getAPI().getEventManager().registerListener(this);
            plugin.getLogger().info("[ModDetection] Enabled with " + bannedMods.size() + " banned mods");
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;
        
        // Get player info from the event
        UUID playerUUID = event.getUser().getUUID();
        String playerName = event.getUser().getName();
        
        // Handle Play phase packets (after player has joined)
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            try {
                WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
                String channel = packet.getChannelName().toString().toLowerCase();
                byte[] data = packet.getData();
                
                // Check if this is a brand packet (contains client type)
                if (channel.equals("minecraft:brand") || channel.equals("minecraft:register")) {
                    String brand = new String(data, StandardCharsets.UTF_8).toLowerCase();
                    detectModsFromBrand(playerUUID, playerName, brand);
                }
                
                // Check for known mod channels
                detectModFromChannel(playerUUID, playerName, channel);
                
            } catch (Exception e) {
                // Ignore malformed packets
            }
        }
        
        // Handle Configuration phase packets (PRE-JOIN detection)
        // This is where Fabric sends the mod list before the player enters the world
        if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            try {
                WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
                String channel = packet.getChannelName().toString().toLowerCase();
                byte[] data = packet.getData();
                
                // Check for Fabric mod list packet
                if (channel.equals("fabric:registry/sync") || channel.equals("minecraft:register")) {
                    String payload = new String(data, StandardCharsets.UTF_8).toLowerCase();
                    checkForBannedModsPreJoin(event, playerUUID, playerName, payload);
                }
                
                // Check channel name itself for mod indicators
                detectModFromChannel(playerUUID, playerName, channel);
                
            } catch (Exception e) {
                // Ignore malformed packets
            }
        }
    }

    /**
     * Detect mods from the brand string (fabric, forge, etc.)
     * Brand format is typically: "fabric" or "forge" or "vanilla" with optional suffixes
     */
    private void detectModsFromBrand(UUID playerUUID, String playerName, String brand) {
        if (brand == null || brand.isEmpty()) return;
        
        String lowerBrand = brand.toLowerCase();
        
        // Check for banned mod names in the brand first
        for (String bannedMod : bannedMods) {
            if (lowerBrand.contains(bannedMod.toLowerCase())) {
                kickPlayer(playerUUID, playerName, bannedMod, "brand: " + brand);
                return;
            }
        }
        
        // Detect mod loader type - use word boundaries to avoid false positives
        // Check for Fabric (but not if it's part of another word)
        if (lowerBrand.equals("fabric") || lowerBrand.startsWith("fabric ") || lowerBrand.contains("(fabric)")) {
            logMod(playerUUID, "fabric-loader");
        }
        // Check for Forge (but not if it's part of "fabric")
        else if (lowerBrand.equals("forge") || lowerBrand.startsWith("forge ") || lowerBrand.contains("(forge)")) {
            logMod(playerUUID, "forge-loader");
        }
        // Check for Quilt (Fabric alternative)
        else if (lowerBrand.contains("quilt")) {
            logMod(playerUUID, "quilt-loader");
        }
        // Check for NeoForge
        else if (lowerBrand.contains("neoforge")) {
            logMod(playerUUID, "neoforge-loader");
        }
    }

    /**
     * Detect mods from channel names
     */
    private void detectModFromChannel(UUID playerUUID, String playerName, String channel) {
        if (channel == null) return;
        
        // Check banned channels first
        for (String bannedChannel : bannedChannels) {
            if (channel.contains(bannedChannel.toLowerCase())) {
                kickPlayer(playerUUID, playerName, bannedChannel, "channel: " + channel);
                return;
            }
        }
        
        // Check for known mod channels
        for (String knownChannel : KNOWN_MOD_CHANNELS) {
            if (channel.contains(knownChannel)) {
                // Extract mod name from channel
                String modName = extractModName(channel);
                if (modName != null) {
                    logMod(playerUUID, modName);
                    
                    // Check if this mod is banned
                    if (bannedMods.contains(modName.toLowerCase())) {
                        kickPlayer(playerUUID, playerName, modName, "channel: " + channel);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Extract mod name from a channel string
     */
    private String extractModName(String channel) {
        // Remove common prefixes
        String name = channel.replace("fabric:", "")
                            .replace("forge:", "")
                            .replace("minecraft:", "")
                            .replace("fml:", "");
        
        // Take first segment as mod name
        if (name.contains(":")) {
            name = name.split(":")[0];
        }
        
        return name.isEmpty() ? null : name;
    }

    /**
     * Log a detected mod for a player
     */
    private void logMod(UUID playerUUID, String modName) {
        playerMods.computeIfAbsent(playerUUID, k -> ConcurrentHashMap.newKeySet()).add(modName);
        
        if (plugin.getConfig().getBoolean("mod-detection.log-detected", true)) {
            plugin.getLogger().info("[ModDetection] Detected mod for " + playerUUID + ": " + modName);
        }
    }

    /**
     * Check for banned mods in pre-join phase and disconnect if found
     */
    private void checkForBannedModsPreJoin(PacketReceiveEvent event, UUID playerUUID, String playerName, String payload) {
        // Check each banned mod against the payload
        for (String bannedMod : bannedMods) {
            if (payload.contains(bannedMod.toLowerCase())) {
                plugin.getLogger().warning("[ModDetection] PRE-JOIN: Rejecting " + playerName + " for banned mod: " + bannedMod);
                
                // Kick message
                String message = kickMessage.replace("{mod}", bannedMod);
                
                // Close the connection (this disconnects the player before they join)
                event.getUser().closeConnection();
                
                // Also log for staff
                if (plugin.getConfig().getBoolean("mod-detection.broadcast-kicks", true)) {
                    String broadcastMsg = "§8[§cModDetection§8] §e" + playerName + " §7was blocked from joining (banned mod: §c" + bannedMod + "§7)";
                    Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("allium.moddetection.notify"))
                        .forEach(p -> p.sendMessage(broadcastMsg));
                }
                
                return;
            }
        }
        
        // Log all detected mods for debugging if enabled
        if (plugin.getConfig().getBoolean("mod-detection.log-detected", true)) {
            plugin.getLogger().info("[ModDetection] Pre-join mod list payload for " + playerName + ": " + payload.substring(0, Math.min(200, payload.length())));
        }
    }

    /**
     * Kick a player for using a banned mod
     */
    private void kickPlayer(UUID uuid, String playerName, String modName, String detectionMethod) {
        // Get Bukkit player
        Player bukkitPlayer = Bukkit.getPlayer(uuid);
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) return;
        
        // Log the kick
        plugin.getLogger().warning("[ModDetection] Kicking " + playerName + " for banned mod: " + modName + " (" + detectionMethod + ")");
        
        // Kick the player using kick() method (not deprecated)
        String message = kickMessage.replace("{mod}", modName);
        SchedulerAdapter.runTask(plugin, () -> {
            bukkitPlayer.kick(net.kyori.adventure.text.Component.text(message));
        });
        
        // Broadcast to staff if configured
        if (plugin.getConfig().getBoolean("mod-detection.broadcast-kicks", true)) {
            String broadcastMsg = "§8[§cModDetection§8] §e" + playerName + " §7was kicked for using §c" + modName + "§7.";
            Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("allium.moddetection.notify"))
                .forEach(p -> p.sendMessage(broadcastMsg));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        
        // Schedule a check after player fully joins to see what mods were detected
        SchedulerAdapter.runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            Set<String> mods = playerMods.get(player.getUniqueId());
            
            if (mods != null && !mods.isEmpty()) {
                plugin.getLogger().info("[ModDetection] " + player.getName() + " has mods: " + String.join(", ", mods));
                
                // Notify staff
                if (plugin.getConfig().getBoolean("mod-detection.notify-staff", true)) {
                    String msg = "§8[§cModDetection§8] §7" + player.getName() + " §7is using mods: §e" + String.join(", ", mods);
                    Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("allium.moddetection.notify"))
                        .forEach(p -> p.sendMessage(msg));
                }
            }
        }, 60L); // 3 second delay
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        // Clean up mod data when player leaves
        playerMods.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Get detected mods for a player
     */
    public Set<String> getPlayerMods(UUID playerUUID) {
        return playerMods.getOrDefault(playerUUID, Collections.emptySet());
    }

    /**
     * Check if a player has a specific mod
     */
    public boolean hasMod(UUID playerUUID, String modName) {
        Set<String> mods = playerMods.get(playerUUID);
        if (mods == null) return false;
        
        return mods.stream().anyMatch(m -> m.equalsIgnoreCase(modName));
    }
}
