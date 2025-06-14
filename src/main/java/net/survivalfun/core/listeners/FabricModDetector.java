package net.survivalfun.core.listeners;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserConnectEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;


public class FabricModDetector implements PacketListener {
    private final Map<UUID, Set<String>> playerMods = new HashMap<>();
    private final PluginStart plugin;
    private final Lang lang;
    private final Set<UUID> staffReceivingAlerts = new HashSet<>(); // Staff who have alerts enabled

    // Define colors for the new alert style
    private static final TextColor ALERT_BORDER_COLOR = TextColor.fromHexString("#FFA500"); // Orange
    // private static final TextColor ALERT_TITLE_COLOR = TextColor.fromHexString("#FFC0CB"); // Pink - Not used in current lang.yml example
    private static final TextColor PLAYER_NAME_COLOR = TextColor.fromHexString("#FFFF00"); // Yellow - Used by hover
    // private static final TextColor MODS_LABEL_COLOR = TextColor.fromHexString("#ADD8E6"); // Light Blue - Not used in current lang.yml example
    // private static final TextColor MODS_LIST_COLOR = TextColor.fromHexString("#FFFFFF");   // White - Not used in current lang.yml example
    private static final TextColor INFO_TEXT_COLOR = TextColor.fromHexString("#E0E0E0"); // Light Gray - Used by Teleport text
    private static final TextColor HOVER_TEXT_COLOR = TextColor.fromHexString("#AFEEEE"); // PaleTurquoise - Used by hover


    public FabricModDetector(PluginStart plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
        plugin.getLogger().info("[FabricDetector] Initializing...");
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            String channel = packet.getChannelName();
            // Removed verbose logging of every plugin message for brevity unless debugging
            // plugin.getLogger().info("[FabricDetector] Received PLUGIN_MESSAGE on channel: " + channel);

            if (channel.equals("minecraft:register") || channel.equals("fabric:registry/sync") || channel.equals("fabric:networking/register")) {
                Player player = (Player) event.getPlayer();
                if (player == null) return;

                Set<String> mods = playerMods.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
                Set<String> previouslyDetectedMods = new HashSet<>(mods); // Copy for comparison

                try {
                    byte[] data = packet.getData();
                    if (data.length > 0) {
                        String payload = new String(data, StandardCharsets.UTF_8);
                        // Removed verbose logging of payload unless debugging
                        // plugin.getLogger().info("[FabricDetector] Player: " + player.getName() + ", Channel: " + channel + ", Raw Payload: " + payload.replace("\0", "<NULL>"));

                        Set<String> newlyDetectedStringsInThisPacket = new HashSet<>();

                        if (channel.equals("minecraft:register")) {
                            String[] registeredChannels = payload.split("\0");
                            for (String registeredChannel : registeredChannels) {
                                String potentialModId = registeredChannel.trim().toLowerCase();
                                if (potentialModId.isEmpty()) continue;

                                if (potentialModId.startsWith("fabric:") || potentialModId.equals("fabric")) {
                                    potentialModId = "fabric"; // Generic fabric indicator
                                } else if (potentialModId.contains(":")) {
                                    potentialModId = potentialModId.substring(0, potentialModId.indexOf(':')).toLowerCase();
                                }
                                // Normalize common mod channel prefixes if desired
                                if (potentialModId.startsWith("worldeditcui")) potentialModId = "worldeditcui";

                                if (!previouslyDetectedMods.contains(potentialModId)) {
                                    mods.add(potentialModId);
                                    newlyDetectedStringsInThisPacket.add(potentialModId);
                                }
                            }
                        } else { // Handling for older fabric channels (fabric:registry/sync, fabric:networking/register)
                            if (payload.toLowerCase().contains("fabric")) {
                                String[] parts = payload.split("[\n;\0]"); // Split by newline, semicolon, or null char
                                for (String part : parts) {
                                    String trimmedPart = part.trim().toLowerCase();
                                    if (trimmedPart.isEmpty()) continue;

                                    if (trimmedPart.startsWith("fabric:") || trimmedPart.equals("fabric")) {
                                        if (!previouslyDetectedMods.contains("fabric")) {
                                            mods.add("fabric");
                                            newlyDetectedStringsInThisPacket.add("fabric");
                                        }
                                    } else if (trimmedPart.contains(":")) {
                                        String modId = trimmedPart.split(":")[0].trim().toLowerCase();
                                        if (!modId.isEmpty() && !previouslyDetectedMods.contains(modId)) {
                                            mods.add(modId);
                                            newlyDetectedStringsInThisPacket.add(modId);
                                        }
                                    }
                                }
                            }
                        }

                        if (!newlyDetectedStringsInThisPacket.isEmpty()) {
                            plugin.getLogger().info("[FabricDetector] Newly detected mod strings for " + player.getName() + ": " + String.join(", ", newlyDetectedStringsInThisPacket));
                            plugin.getLogger().info("[FabricDetector] Current full list of detected mods for " + player.getName() + ": " + String.join(", ", mods));
                            alertStaff(player, mods, newlyDetectedStringsInThisPacket); // Pass newly detected too
                        }
                        // Removed "no new mods" log for less spam
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[FabricDetector] Error parsing Fabric mod list for " + (player != null ? player.getName() : "unknown player") + ": " + e.getMessage());
                     // e.printStackTrace(); // Consider for detailed debugging
                }
            }
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // Not used
    }

    @Override
    public void onUserConnect(UserConnectEvent event) {
        // Potentially clear data if a player reconnects quickly to ensure fresh detection
        if (event.getUser() != null && event.getUser().getUUID() != null) {
            clearPlayerData(event.getUser().getUUID()); // Clear on connect to reset state
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        if (event.getUser() != null && event.getUser().getUUID() != null) {
            clearPlayerData(event.getUser().getUUID());
        }
    }

    // Updated alertStaff method
    private void alertStaff(Player player, Set<String> allMods, Set<String> newlyDetectedMods) {
        String playerName = player.getName();
        
        // Get the raw message format from lang.yml (assuming plugin.getLangString exists)
        String rawMessageFormat = lang.get("fabric.alert");

        String newModsString = newlyDetectedMods.isEmpty() ? "None" : newlyDetectedMods.stream().map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1)).collect(Collectors.joining(", "));
        String allModsString = allMods.isEmpty() ? "Unknown" : allMods.stream().map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1)).collect(Collectors.joining(", "));

        String messageWithPlaceholders = rawMessageFormat
                .replace("{name}", playerName)
                .replace("{mods}", allModsString)
                .replace("{new_mods}", newModsString);

        // Convert the legacy formatted string (with & codes) to a Component
        TextComponent alertMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(messageWithPlaceholders);

        // Build the final component with hover/click events
        TextComponent finalAlert = Component.text()
                .append(Component.text("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬\n", ALERT_BORDER_COLOR)) // Top border
                .append(alertMessage)
                .append(Component.text("\n")) // Spacer line
                .append(Component.text("[Teleport to ", INFO_TEXT_COLOR))
                .append(Component.text(playerName, PLAYER_NAME_COLOR))
                .append(Component.text("]", INFO_TEXT_COLOR))
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport to " + playerName, HOVER_TEXT_COLOR)))
                .clickEvent(ClickEvent.runCommand("/tp " + playerName))
                .append(Component.text("\n▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬", ALERT_BORDER_COLOR)) // Bottom border
                .build();
        
        plugin.getLogger().info("[FabricDetector] Alerting staff. Message: " + LegacyComponentSerializer.legacySection().serialize(finalAlert));

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("core.staff") && areAlertsEnabled(staff.getUniqueId())) {
                staff.sendMessage(finalAlert);
            }
        }
    }


    public Set<String> getPlayerMods(UUID playerId) {
        return playerMods.getOrDefault(playerId, new HashSet<>());
    }

    public boolean hasMod(UUID playerId, String modId) {
        return getPlayerMods(playerId).contains(modId.toLowerCase());
    }

    public void clearPlayerData(UUID playerId) {
        plugin.getLogger().info("[FabricDetector] Clearing data for player UUID: " + playerId);
        playerMods.remove(playerId);
        // staffReceivingAlerts.remove(playerId); // Decide if preferences should clear on disconnect
    }

    // Methods for managing staff alert preferences
    public boolean toggleAlerts(UUID staffId) {
        if (staffReceivingAlerts.contains(staffId)) {
            staffReceivingAlerts.remove(staffId);
            return false; // Alerts disabled
        } else {
            staffReceivingAlerts.add(staffId);
            return true; // Alerts enabled
        }
    }

    public void setAlerts(UUID staffId, boolean enabled) {
        if (enabled) {
            staffReceivingAlerts.add(staffId);
        } else {
            staffReceivingAlerts.remove(staffId);
        }
    }

    public boolean areAlertsEnabled(UUID staffId) {
        return staffReceivingAlerts.contains(staffId);
    }
}