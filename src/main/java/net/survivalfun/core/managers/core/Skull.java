package net.survivalfun.core.managers.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.survivalfun.core.PluginStart;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Skull manager class
 */
public class Skull implements CommandExecutor {
    private static OkHttpClient httpClient = new OkHttpClient();

    private final PluginStart plugin;

    public Skull(PluginStart plugin) {
        this.plugin = plugin;
    }
    
    @SuppressWarnings("deprecation")
    public static ItemStack createSkullWithTexture(String texture, String playerName) {
        try {
            if (texture == null || texture.isEmpty()) {
                return null;
            }
            
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta == null) {
                return null;
            }
            
            // Set the display name if playerName is provided
            if (playerName != null && !playerName.isEmpty()) {
                meta.displayName(net.kyori.adventure.text.Component.text(
                    playerName + "'s Head",
                    net.kyori.adventure.text.format.NamedTextColor.WHITE
                ));
            }
            
            // Create a GameProfile with random UUID and the texture
            UUID profileId = UUID.randomUUID();
            String profileName = (playerName != null && !playerName.isEmpty()) ? playerName : "Custom";
            
            try {
                // Method 1: Try the modern PlayerProfile API (1.16.5+)
                try {
                    org.bukkit.profile.PlayerProfile playerProfile = Bukkit.createProfile(profileId, profileName);
                    
                    // Set the texture using the modern API
                    try {
                        // Convert texture to URL if it's just the hash
                        String textureUrl = texture;
                        if (!texture.startsWith("http")) {
                            textureUrl = "https://textures.minecraft.net/texture/" + texture;
                        }
                        
                        playerProfile.getTextures().setSkin(new java.net.URL(textureUrl));
                        meta.setOwnerProfile(playerProfile);
                        head.setItemMeta(meta);
                        return head;
                    } catch (Exception e) {
                        Bukkit.getLogger().log(Level.WARNING, "Failed to set texture via PlayerProfile: " + e.getMessage());
                        // Fall through to next method
                    }
                } catch (Exception e) {
                    // PlayerProfile API not available, try reflection
                }
                
                // Method 2: Use GameProfile with reflection (legacy support)
                try {
                    GameProfile profile = new GameProfile(profileId, profileName);
                    String texturesValue = texture;
                    
                    // If texture isn't a full base64 string, encode it
                    if (!texture.startsWith("eyJ")) {  // Base64 JSON starts with eyJ
                        String json = String.format("{\"textures\":{\"SKIN\":{\"url\":\"%s\"}}}", 
                            "https://textures.minecraft.net/texture/" + texture);
                        texturesValue = java.util.Base64.getEncoder().encodeToString(json.getBytes());
                    }
                    
                    profile.getProperties().put("textures", new Property("textures", texturesValue));
                    
                    // Set the profile using reflection
                    java.lang.reflect.Field profileField = meta.getClass().getDeclaredField("profile");
                    profileField.setAccessible(true);
                    profileField.set(meta, profile);
                    
                    head.setItemMeta(meta);
                    return head;
                } catch (Exception e) {
                    Bukkit.getLogger().log(Level.WARNING, "Failed to set texture via reflection: " + e.getMessage());
                }
                
                return null;
                
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.SEVERE, "Error creating custom skull", e);
                return null;
            }
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Unexpected error in createSkullWithTexture", e);
            return null;
        }
    }
    
    /**
     * Alternative method to set texture via CraftBukkit reflection
     */
    @SuppressWarnings("unused")
    private static ItemStack setTextureViaCraftBukkit(ItemStack head, GameProfile profile, String texture) {
        try {
            // Try to use CraftBukkit's internal methods to set the texture
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta == null) return head;
            
            // Try to find CraftMetaSkull class and set the profile
            try {
                Class<?> craftMetaSkullClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftMetaSkull");
                if (craftMetaSkullClass.isInstance(meta)) {
                    Field profileField = craftMetaSkullClass.getDeclaredField("profile");
                    profileField.setAccessible(true);
                    profileField.set(meta, profile);
                    head.setItemMeta(meta);
                    return head;
                }
            } catch (Exception e) {
                // CraftBukkit approach failed
            }
            
            // Try alternative field names
            try {
                Field[] fields = meta.getClass().getDeclaredFields();
                for (Field field : fields) {
                    if (field.getType().getName().contains("GameProfile") || 
                        field.getName().toLowerCase().contains("profile")) {
                        field.setAccessible(true);
                        field.set(meta, profile);
                        head.setItemMeta(meta);
                        return head;
                    }
                }
            } catch (Exception e) {
                // Field search failed
            }
            
            return head;
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "CraftBukkit texture setting failed", e);
            return head;
        }
    }
    
    /**
     * Creates a skull with the specified texture (overloaded method for backward compatibility)
     */
    @Nullable
    public static ItemStack createSkullWithTexture(String texture) {
        return createSkullWithTexture(texture, null);
    }

    /**
     * Creates a skull for the specified player name using only Minecraft-Heads API
     * @param playerName The name of the player to get the skull for
     * @return ItemStack with the player's head, or null if failed
     */
    @Nullable
    public static ItemStack createPlayerHead(String playerName) {
        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
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
     * Fetches a player head using only Minecraft-Heads API 2.0 based on the player name
     * @param playerName The name of the player to fetch the head for
     * @return ItemStack with the player's head texture, or null if failed
     */
    /**
     * Fetches a player's head texture using Mojang's API and creates a skull item.
     * This method first gets the player's UUID from their username, then fetches their profile
     * to get the texture data, and finally creates a skull with that texture.
     * 
     * @param playerName The name of the player to get the head of
     * @return ItemStack containing the player's head, or null if it couldn't be fetched
     */
    @Nullable
    public static ItemStack fetchPlayerHeadFromName(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            Bukkit.getLogger().log(Level.WARNING, "Cannot fetch player head for null or empty player name");
            return null;
        }

        try {
            // Step 1: Get the player's UUID from their username
            String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
            Request uuidRequest = new Request.Builder()
                    .url(uuidUrl)
                    .build();

            String uuid;
            try (Response uuidResponse = httpClient.newCall(uuidRequest).execute()) {
                if (!uuidResponse.isSuccessful() || uuidResponse.body() == null) {
                    Bukkit.getLogger().log(Level.WARNING, "Failed to fetch UUID for " + playerName + ": " + 
                            (uuidResponse.body() != null ? uuidResponse.body().string() : "Empty response"));
                    return null;
                }
                
                String uuidBody = uuidResponse.body().string();
                JsonObject uuidJson = JsonParser.parseString(uuidBody).getAsJsonObject();
                uuid = uuidJson.get("id").getAsString();
                
                // Format UUID with hyphens (8-4-4-4-12 format)
                if (uuid.length() == 32) {
                    uuid = String.format("%s-%s-%s-%s-%s",
                        uuid.substring(0, 8),
                        uuid.substring(8, 12),
                        uuid.substring(12, 16),
                        uuid.substring(16, 20),
                        uuid.substring(20));
                }
            }

            // Step 2: Get the player's profile with texture data
            String profileUrl = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false";
            Request profileRequest = new Request.Builder()
                    .url(profileUrl)
                    .build();

            try (Response profileResponse = httpClient.newCall(profileRequest).execute()) {
                if (!profileResponse.isSuccessful() || profileResponse.body() == null) {
                    Bukkit.getLogger().log(Level.WARNING, "Failed to fetch profile for " + playerName);
                    return null;
                }
                
                String profileBody = profileResponse.body().string();
                JsonObject profileJson = JsonParser.parseString(profileBody).getAsJsonObject();
                
                // Get the texture value from the profile
                JsonArray properties = profileJson.getAsJsonArray("properties");
                for (JsonElement element : properties) {
                    JsonObject property = element.getAsJsonObject();
                    if (property.get("name").getAsString().equals("textures")) {
                        String textureValue = property.get("value").getAsString();
                        // Create and return the skull with the texture
                        return createSkullWithTexture(textureValue, playerName);
                    }
                }
                
                Bukkit.getLogger().log(Level.WARNING, "No texture data found for " + playerName);
                return null;
            }
            
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Error fetching player head for " + playerName, e);
            return null;
        }
    }
    

    


    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        
        final Player player = (Player) sender;

        // Check permission
        if (!player.hasPermission("core.skull")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        // Check arguments
        if (args.length != 1) {
            player.sendMessage("§cUsage: /" + label + " <player|base64:texture>");
            return true;
        }

        final String targetName = String.join(" ", args).trim();
        
        // Handle base64 texture
        if (targetName.startsWith("base64:")) {
            String texture = targetName.substring(7);
            ItemStack skull = createSkullWithTexture(texture, "Custom");
            if (skull != null) {
                player.getInventory().addItem(skull);
                player.sendMessage("§aCustom head created successfully!");
            } else {
                player.sendMessage("§cFailed to create custom head.");
            }
            return true;
        }
        
        // Handle player head
        player.sendMessage("§aFetching head for §e" + targetName + "§a...");
        
        // Run the API call asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Try to get the player's UUID first
                String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + targetName;
                Request uuidRequest = new Request.Builder()
                        .url(uuidUrl)
                        .build();

                try (Response uuidResponse = httpClient.newCall(uuidRequest).execute()) {
                    if (!uuidResponse.isSuccessful() || uuidResponse.body() == null) {
                        // Fallback to offline player if Mojang API fails
                        Bukkit.getScheduler().runTask(plugin, () -> 
                            handleOfflinePlayer(player, targetName)
                        );
                        return;
                    }
                    
                    String uuidBody = uuidResponse.body().string();
                    JsonObject uuidJson = JsonParser.parseString(uuidBody).getAsJsonObject();
                    String uuid = uuidJson.get("id").getAsString();
                    
                    // Get the player's profile with texture data
                    String profileUrl = "https://sessionserver.mojang.com/session/minecraft/profile/" + 
                                     uuid + "?unsigned=false";
                    Request profileRequest = new Request.Builder()
                            .url(profileUrl)
                            .build();

                    try (Response profileResponse = httpClient.newCall(profileRequest).execute()) {
                        if (!profileResponse.isSuccessful() || profileResponse.body() == null) {
                            // Fallback to offline player if profile fetch fails
                            Bukkit.getScheduler().runTask(plugin, () -> 
                                handleOfflinePlayer(player, targetName)
                            );
                            return;
                        }
                        
                        String profileBody = profileResponse.body().string();
                        JsonObject profileJson = JsonParser.parseString(profileBody).getAsJsonObject();
                        
                        // Get the texture value
                        JsonArray properties = profileJson.getAsJsonArray("properties");
                        for (JsonElement element : properties) {
                            JsonObject property = element.getAsJsonObject();
                            if (property.get("name").getAsString().equals("textures")) {
                                String textureValue = property.get("value").getAsString();
                                
                                // Create and give the skull on the main thread
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    ItemStack skullItem = createSkullWithTexture(textureValue, targetName);
                                    if (skullItem != null) {
                                        giveSkullItem(player, skullItem, targetName, "Mojang API");
                                    } else {
                                        // Fallback to offline player if texture creation fails
                                        handleOfflinePlayer(player, targetName);
                                    }
                                });
                                return;
                            }
                        }
                        
                        // No texture property found, fallback to offline player
                        Bukkit.getScheduler().runTask(plugin, () -> 
                            handleOfflinePlayer(player, targetName)
                        );
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error fetching player head", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cAn error occurred while fetching the head. Using offline fallback...");
                    handleOfflinePlayer(player, targetName);
                });
            }
        });
        
        return true;
    }
    
    /**
     * Handles the fallback to offline player when API calls fail
     */
    private void handleOfflinePlayer(Player player, String targetName) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                // Try to get an offline player
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetName);
                if (offlinePlayer.hasPlayedBefore() || offlinePlayer.isOnline()) {
                    ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                    SkullMeta meta = (SkullMeta) skull.getItemMeta();
                    if (meta != null) {
                        meta.setOwningPlayer(offlinePlayer);
                        skull.setItemMeta(meta);
                        giveSkullItem(player, skull, targetName, "offline player");
                        return;
                    }
                }
                // If we get here, we couldn't create the offline player head either
                player.sendMessage("§cCould not find a player with that name.");
            } catch (Exception e) {
                player.sendMessage("§cFailed to create offline player head: " + e.getMessage());
                plugin.getLogger().log(Level.WARNING, "Error creating offline player head", e);
            }
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
                    player.sendMessage("§eYour inventory is full. The head was dropped at your location.");
                } else {
                    player.getInventory().addItem(skull);
                }
                
                player.sendMessage("§aSuccessfully retrieved head for §e" + ownerName + " §a(via " + source + ")");
            } catch (Exception e) {
                plugin.getLogger().warning("Error giving skull to player: " + e.getMessage());
                player.sendMessage("§cAn error occurred while giving you the head.");
            }
        });
    }
}