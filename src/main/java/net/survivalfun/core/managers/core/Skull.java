package net.survivalfun.core.managers.core;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.survivalfun.core.PluginStart;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.json.JSONObject;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Skull manager class
 */
public class Skull implements CommandExecutor {
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

            // Try modern PlayerProfile API (1.16.5+)
            try {
                UUID profileId = UUID.randomUUID();
                String profileName = (playerName != null && !playerName.isEmpty()) ? playerName : "Custom";
                org.bukkit.profile.PlayerProfile playerProfile = Bukkit.createProfile(profileId, profileName);
                String textureUrl = texture;
                if (!texture.startsWith("http")) {
                    textureUrl = "https://textures.minecraft.net/texture/" + texture;
                }
                playerProfile.getTextures().setSkin(new URL(textureUrl));
                meta.setOwnerProfile(playerProfile);
                head.setItemMeta(meta);
                return head;
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.WARNING, "Failed to set texture via PlayerProfile: " + e.getMessage());
            }

            // Fallback to GameProfile with reflection
            try {
                GameProfile profile = new GameProfile(UUID.randomUUID(), playerName != null ? playerName : null);
                String texturesValue = texture;
                if (!texture.startsWith("eyJ")) {
                    String json = String.format("{\"textures\":{\"SKIN\":{\"url\":\"%s\"}}}", 
                        "https://textures.minecraft.net/texture/" + texture);
                    texturesValue = java.util.Base64.getEncoder().encodeToString(json.getBytes());
                }
                profile.getProperties().put("textures", new Property("textures", texturesValue));
                java.lang.reflect.Field profileField = meta.getClass().getDeclaredField("profile");
                profileField.setAccessible(true);
                profileField.set(meta, profile);
                head.setItemMeta(meta);
                return head;
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.WARNING, "Failed to set texture via reflection: " + e.getMessage());
                return null;
            }
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Unexpected error in createSkullWithTexture", e);
            return null;
        }
    }

    @Nullable
    public static ItemStack createSkullWithTexture(String texture) {
        return createSkullWithTexture(texture, null);
    }

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

    @Nullable
    public static ItemStack fetchPlayerHeadFromName(String playerName) {
        try {
            // Step 1: Get UUID from Mojang API
            String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
            HttpURLConnection uuidConnection = (HttpURLConnection) new URL(uuidUrl).openConnection();
            uuidConnection.setRequestMethod("GET");
            uuidConnection.setConnectTimeout(5000);
            uuidConnection.setReadTimeout(5000);

            int uuidResponseCode = uuidConnection.getResponseCode();
            if (uuidResponseCode != 200) {
                Bukkit.getLogger().log(Level.WARNING, "Failed to fetch UUID for " + playerName + ": HTTP " + uuidResponseCode);
                return null;
            }

            BufferedReader uuidReader = new BufferedReader(new InputStreamReader(uuidConnection.getInputStream()));
            StringBuilder uuidResponse = new StringBuilder();
            String line;
            while ((line = uuidReader.readLine()) != null) {
                uuidResponse.append(line);
            }
            uuidReader.close();

            JSONObject uuidJson = new JSONObject(uuidResponse.toString());
            String uuid = uuidJson.getString("id");
            if (uuid == null || uuid.isEmpty()) {
                Bukkit.getLogger().log(Level.WARNING, "No UUID found for " + playerName);
                return null;
            }

            // Step 2: Get texture from Mojang session server
            String textureUrl = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid;
            HttpURLConnection textureConnection = (HttpURLConnection) new URL(textureUrl).openConnection();
            textureConnection.setRequestMethod("GET");
            textureConnection.setConnectTimeout(5000);
            textureConnection.setReadTimeout(5000);

            int textureResponseCode = textureConnection.getResponseCode();
            if (textureResponseCode != 200) {
                Bukkit.getLogger().log(Level.WARNING, "Failed to fetch texture for UUID " + uuid + ": HTTP " + textureResponseCode);
                return null;
            }

            BufferedReader textureReader = new BufferedReader(new InputStreamReader(textureConnection.getInputStream()));
            StringBuilder textureResponse = new StringBuilder();
            while ((line = textureReader.readLine()) != null) {
                textureResponse.append(line);
            }
            textureReader.close();

            JSONObject textureJson = new JSONObject(textureResponse.toString());
            String base64Texture = textureJson.getJSONArray("properties")
                    .getJSONObject(0)
                    .getString("value");

            // Step 3: Create skull with texture
            return createSkullWithTexture(base64Texture, playerName);

        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "Error fetching player head for " + playerName, e);
            return null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
                giveSkullItem(player, skull, "Custom", "base64 input");
            } else {
                player.sendMessage("§cFailed to create custom head.");
            }
            return true;
        }

        // Handle player head
        player.sendMessage("§aFetching head for §e" + targetName + "§a...");

        // Run the API call asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // First try offline player
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetName);
            if (offlinePlayer.isOnline() || offlinePlayer.hasPlayedBefore()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ItemStack skull = createPlayerHead(targetName);
                    if (skull != null) {
                        giveSkullItem(player, skull, targetName, "offline player");
                    } else {
                        player.sendMessage("§cFailed to create offline player head.");
                    }
                });
                return;
            }

            // Fetch head via Mojang API for offline players
            ItemStack skull = fetchPlayerHeadFromName(targetName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (skull != null) {
                    giveSkullItem(player, skull, targetName, "Mojang API");
                } else {
                    player.sendMessage("§cCould not find a player with that name or failed to fetch head.");
                }
            });
        });

        return true;
    }

    private void giveSkullItem(Player player, ItemStack skull, String ownerName, String source) {
        try {
            if (player.getInventory().firstEmpty() == -1) {
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
    }
}