package net.survivalfun.core.managers.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;

public class Skull implements CommandExecutor {

    private final PluginStart plugin;
    private final Lang lang;

    public Skull(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }
    
    /**
     * Creates a skull with the specified texture URL or base64 texture
     * @param texture The base64 texture string to apply to the skull
     * @return ItemStack with the custom texture, or null if failed
     */
    @Nullable
    public static ItemStack createSkullWithTexture(String texture) {
        try {
            if (texture == null || texture.isEmpty()) {
                return null;
            }
            
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta == null) {
                return null;
            }
            
            // If it's a base64 texture (starts with eyJ)
            if (texture.startsWith("eyJ")) {
                // Use a dummy name to avoid NullPointerException
                GameProfile profile = new GameProfile(UUID.randomUUID(), "Player");
                profile.getProperties().put("textures", new Property("textures", texture));
                
                try {
                    Field profileField = meta.getClass().getDeclaredField("profile");
                    profileField.setAccessible(true);
                    profileField.set(meta, profile);
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    Bukkit.getLogger().log(Level.SEVERE, "Failed to set skull profile", e);
                    return null;
                }
                
                head.setItemMeta(meta);
                return head;
            }
            
            // Create a GameProfile with the texture
            // Use a dummy name to avoid NullPointerException
            GameProfile profile = new GameProfile(UUID.randomUUID(), "Player");
            profile.getProperties().put("textures", new Property("textures", texture));
            
            // Use reflection to set the profile
            try {
                Field profileField = meta.getClass().getDeclaredField("profile");
                profileField.setAccessible(true);
                profileField.set(meta, profile);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                Bukkit.getLogger().log(Level.SEVERE, "Failed to set skull profile", e);
                return null;
            }
            
            head.setItemMeta(meta);
            return head;
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Error creating skull with texture", e);
            return null;
        }
    }

    /**
     * Creates a skull for the specified player name
     * @param playerName The name of the player to get the skull for
     * @return ItemStack with the player's head, or null if failed
     */
    @Nullable
    public static ItemStack createPlayerHead(String playerName) {
        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
            if (!offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline()) {
                // Try to fetch from minecraft-heads.com
                return fetchPlayerHeadFromName(playerName);
            }
            
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta == null) {
                return null;
            }
            
            meta.setOwningPlayer(offlinePlayer);
            head.setItemMeta(meta);
            return head;
            
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Error creating player head", e);
            return null;
        }
    }
    
    /**
     * Fetches a player head from minecraft-heads.com based on the player name
     * @param playerName The name of the player to fetch the head for
     * @return ItemStack with the player's head texture, or null if failed
     */
    @Nullable
    public static ItemStack fetchPlayerHeadFromName(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            Bukkit.getLogger().log(Level.WARNING, "Cannot fetch player head for null or empty player name");
            return null;
        }
        
        try {
            // First try to get UUID from Mojang API
            String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
            String uuidResponse = getUrlContent(uuidUrl);
            
            if (uuidResponse != null && !uuidResponse.isEmpty()) {
                try {
                    // Parse UUID from response
                    JsonObject jsonObject = JsonParser.parseString(uuidResponse).getAsJsonObject();
                    String uuidStr = jsonObject.get("id").getAsString();
                    
                    // Format UUID with dashes
                    UUID uuid = UUID.fromString(
                        uuidStr.replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w+)", 
                            "$1-$2-$3-$4-$5"
                        )
                    );
                    
                    // Get texture from Mojang API
                    String profileUrl = "https://sessionserver.mojang.com/session/minecraft/profile/" + 
                                     uuid.toString().replace("-", "") + "?unsigned=false";
                    String profileResponse = getUrlContent(profileUrl);
                    
                    if (profileResponse != null && !profileResponse.isEmpty()) {
                        // Parse texture value
                        JsonObject profileJson = JsonParser.parseString(profileResponse).getAsJsonObject();
                        String texture = profileJson.getAsJsonArray("properties")
                            .get(0).getAsJsonObject()
                            .get("value").getAsString();
                        
                        // Create skull with texture
                        return createSkullWithTexture(texture);
                    }
                } catch (Exception e) {
                    Bukkit.getLogger().log(Level.WARNING, "Failed to fetch head from Mojang API: " + e.getMessage());
                }
            }
            
            return null;
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "Error fetching player head: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.skull")) {
            label = "§c" + label;
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
            return true;
        }

        // Check arguments
        if (args.length != 1) {
            label = "§c" + label;
            player.sendMessage(lang.get("command-usage").replace("{cmd}", label).replace("{args}", "<player>"));
            return true;
        }

        final String targetName = String.join(" ", args).trim();
        
        // Check if it's a base64 texture
        if (targetName.startsWith("base64:")) {
            String texture = targetName.substring(7);
            ItemStack skull = createSkullWithTexture(texture);
            if (skull != null) {
                player.getInventory().addItem(skull);
                player.sendMessage("§aCustom head created successfully!");
            } else {
                Text.sendErrorMessage(sender, "skull.error", lang);
            }
            return true;
        }
        
        // Check if it's a player name
        // Notify player that we're fetching the head
        player.sendMessage("§aFetching head for " + targetName + "...");
        
        // Run the head getting asynchronously to prevent lag
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // First try to get the offline player
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetName);
                
                // If the player is online, we can get their head directly
                if (offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null) {
                    giveSkull(player, offlinePlayer.getPlayer().getUniqueId(), offlinePlayer.getName(), "online");
                    return;
                }
                
                // If not online, try to get their head using Mojang's API
                try {
                    // Get UUID from Mojang's API
                    String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + targetName;
                    String uuidResponse = getUrlContent(uuidUrl);
                    
                    if (uuidResponse != null && !uuidResponse.isEmpty()) {
                        try {
                            // Parse UUID from response using Gson
                            JsonObject jsonObject = JsonParser.parseString(uuidResponse).getAsJsonObject();
                            String uuidStr = jsonObject.get("id").getAsString();
                            
                            // Format UUID with dashes
                            UUID uuid = UUID.fromString(
                                uuidStr.replaceFirst(
                                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w+)", 
                                    "$1-$2-$3-$4-$5"
                                )
                            );
                            
                            // Now get the texture
                            String profileUrl = "https://sessionserver.mojang.com/session/minecraft/profile/" + 
                                             uuid.toString().replace("-", "") + "?unsigned=false";
                            String profileResponse = getUrlContent(profileUrl);
                            
                            if (profileResponse != null && !profileResponse.isEmpty()) {
                                // Parse texture value using Gson
                                JsonObject profileJson = JsonParser.parseString(profileResponse).getAsJsonObject();
                                String texture = profileJson.getAsJsonArray("properties")
                                    .get(0).getAsJsonObject()
                                    .get("value").getAsString();
                                
                                // Create and give the skull with the texture
                                ItemStack skull = createSkullWithTexture(texture);
                                if (skull != null) {
                                    giveSkullItem(player, skull, targetName, "Mojang API");
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to fetch head from Mojang API: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to fetch head from Mojang API: " + e.getMessage());
                }
                
                // If we got here, fall back to the offline method
                giveOfflineSkull(player, offlinePlayer, targetName);
                
            } catch (Exception e) {
                plugin.getLogger().warning("Error getting player head: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Text.sendErrorMessage(sender, "skull.error", lang);
                });
            }
        });
        
        return true;
    }
    
    /**
     * Gets the content of a URL as a string
     */
    private static String getUrlContent(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URI(urlString).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            if (connection.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    return response.toString();
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("Error fetching URL " + urlString + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }
    
    /**
     * Gives a player a skull with the specified texture
     */
    private void giveSkull(Player player, UUID ownerUuid, String ownerName, String source) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        
        if (meta != null) {
            try {
                // Try to set the owner using the UUID
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerUuid));
                skull.setItemMeta(meta);
                
                giveSkullItem(player, skull, ownerName, source);
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to set skull owner: " + e.getMessage());
            }
        }
        
        // If we got here, something went wrong
        Bukkit.getScheduler().runTask(plugin, () -> {
            Text.sendErrorMessage(player, "skull.error", lang);
        });
    }
    
    /**
     * Gives a skull to a player using the offline method
     */
    private void giveOfflineSkull(Player player, OfflinePlayer offlinePlayer, String targetName) {
        try {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            
            if (meta != null) {
                meta.setOwningPlayer(offlinePlayer);
                skull.setItemMeta(meta);
                
                giveSkullItem(player, skull, targetName, "offline");
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create offline skull: " + e.getMessage());
        }
        
        // If we got here, something went wrong
        Bukkit.getScheduler().runTask(plugin, () -> {
            Text.sendErrorMessage(player, "skull.error", lang);
        });
    }
    
    /**
     * Gives a skull item to a player
     */
    private void giveSkullItem(Player player, ItemStack skull, String ownerName, String source) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (player.getInventory().firstEmpty() == -1) {
                    // Inventory is full, drop the item
                    player.getWorld().dropItem(player.getLocation(), skull);
                    player.sendMessage(lang.get("inventory-full"));
                } else {
                    player.getInventory().addItem(skull);
                }
                
                player.sendMessage("§aSuccessfully retrieved head for " + ownerName + " (via " + source + ")");
            } catch (Exception e) {
                plugin.getLogger().warning("Error giving skull to player: " + e.getMessage());
                Text.sendErrorMessage(player, "skull.error", lang);
            }
        });
    }
}
