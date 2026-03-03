package net.survivalfun.core.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * Skull manager class. Uses Bukkit PlayerProfile API (EssentialsX-style) when available,
 * with Mojang/Minecraft-Heads HTTP as fallback.
 */
public class Skull implements CommandExecutor {

    private static final boolean PLAYER_PROFILE_SUPPORTED;
    static {
        boolean supported = false;
        try {
            Class.forName("org.bukkit.profile.PlayerProfile");
            supported = true;
        } catch (ClassNotFoundException ignored) {}
        PLAYER_PROFILE_SUPPORTED = supported;
    }
    private static final String MINECRAFT_HEADS_API = "https://minecraft-heads.com/scripts/api.php?player=";
    private static final String MOJANG_PROFILE_API = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String MOJANG_SESSION_API = "https://sessionserver.mojang.com/session/minecraft/profile/";

    public Skull(PluginStart plugin) {
        // Plugin parameter kept for compatibility but not used
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
                Text.sendDebugLog(WARN, "Failed to set texture via PlayerProfile: " + e.getMessage());
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
                Text.sendDebugLog(WARN, "Failed to set texture via reflection: " + e.getMessage());
                return null;
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Unexpected error in createSkullWithTexture", e);
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
            Text.sendDebugLog(ERROR, "Error creating player head", e);
            return null;
        }
    }

    /**
     * Fetches player head via Bukkit PlayerProfile API (EssentialsX-style).
     * No HTTP calls - Bukkit fetches from Mojang internally.
     * Requires 1.18.1+.
     */
    @Nullable
    public static ItemStack fetchPlayerHeadViaProfile(String playerName) {
        if (!PLAYER_PROFILE_SUPPORTED) return null;
        try {
            org.bukkit.profile.PlayerProfile profile = Bukkit.getServer().createPlayerProfile(null, playerName);
            profile = profile.update().join();
            if (profile == null) return null;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta == null) return null;

            meta.setOwnerProfile(profile);
            head.setItemMeta(meta);
            return head;
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "PlayerProfile.update failed for " + playerName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetches player head using Minecraft-Heads API 2.0
     */
    @Nullable
    public static ItemStack fetchPlayerHeadFromMinecraftHeads(String playerName) {
        try {
            String response = httpGet(MINECRAFT_HEADS_API + playerName, "Minecraft-Heads API", playerName);
            if (response == null || response.isEmpty()) {
                Text.sendDebugLog(WARN, "Empty response from Minecraft-Heads API for " + playerName);
                return null;
            }

            String trimmed = response.trim();
            
            // Check if the response looks like JSON (API may return HTML or redirect now)
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                Text.sendDebugLog(WARN, "Minecraft-Heads API returned non-JSON for " + playerName + ", trying Mojang");
                ItemStack mojangHead = fetchPlayerHeadFromMojang(playerName);
                if (mojangHead != null) return mojangHead;
                return null;
            }
            
            JSONObject json;
            try {
                if (trimmed.startsWith("[")) {
                    JSONArray array = new JSONArray(trimmed);
                    if (array.length() == 0) {
                        Text.sendDebugLog(WARN, "Minecraft-Heads API returned empty array for " + playerName);
                        return null;
                    }
                    json = array.getJSONObject(0);
                } else {
                    json = new JSONObject(trimmed);
                }
                
                if (!json.has("textures")) {
                    Text.sendDebugLog(WARN, "No texture data found in Minecraft-Heads API response for " + playerName);
                    return null;
                }

                JSONObject textures = json.getJSONObject("textures");
                if (!textures.has("skin")) {
                    Text.sendDebugLog(WARN, "No skin texture found in Minecraft-Heads API for " + playerName);
                    return null;
                }

                JSONObject skin = textures.getJSONObject("skin");
                if (!skin.has("data")) {
                    Text.sendDebugLog(WARN, "No skin data found in Minecraft-Heads API for " + playerName);
                    return null;
                }

                String textureData = skin.getString("data");
                ItemStack skull = createSkullWithTexture(textureData, playerName);
                if (skull != null) {
                    Text.sendDebugLog(INFO, "Successfully got head from Minecraft-Heads API for " + playerName);
                    return skull;
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Error parsing Minecraft-Heads API response for " + playerName + ": " + e.getMessage());
                // Fall through to try Mojang API or other methods
            }
            
            // If we get here, the Minecraft-Heads API failed, try Mojang API as fallback
            Text.sendDebugLog(INFO, "Falling back to Mojang API for " + playerName);
            ItemStack mojangHead = fetchPlayerHeadFromMojang(playerName);
            if (mojangHead != null) {
                return mojangHead;
            }
            
            return null;

        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error fetching player head from Minecraft-Heads API for " + playerName, e);
            return null;
        }
    }

    @Nullable
    public static ItemStack fetchPlayerHeadFromMojang(String playerName) {
        try {
            String uuid = fetchUuidFromMojang(playerName);
            if (uuid == null || uuid.isEmpty()) {
                return null;
            }

            String profileResponse = httpGet(MOJANG_SESSION_API + uuid + "?unsigned=false", "Mojang session API", playerName);
            if (profileResponse == null || profileResponse.isEmpty()) {
                return null;
            }

            JSONObject profileJson = new JSONObject(profileResponse);
            JSONArray properties = profileJson.optJSONArray("properties");
            if (properties == null) {
                Text.sendDebugLog(WARN, "No properties array present for Mojang profile " + playerName);
                return null;
            }

            for (int i = 0; i < properties.length(); i++) {
                JSONObject property = properties.optJSONObject(i);
                if (property == null) {
                    continue;
                }

                String name = property.optString("name", "");
                if (!"textures".equalsIgnoreCase(name)) {
                    continue;
                }

                String textureValue = property.optString("value", null);
                if (textureValue == null || textureValue.isEmpty()) {
                    continue;
                }

                return createSkullWithTexture(textureValue, playerName);
            }

            Text.sendDebugLog(WARN, "No texture property found for Mojang profile " + playerName);
            return null;
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error fetching player head from Mojang for " + playerName, e);
            return null;
        }
    }

    @Nullable
    private static String fetchUuidFromMojang(String playerName) {
        try {
            String response = httpGet(MOJANG_PROFILE_API + playerName, "Mojang profile API", playerName);
            if (response == null || response.isEmpty()) {
                return null;
            }

            JSONObject json = new JSONObject(response);
            return json.optString("id", null);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error fetching Mojang UUID for " + playerName, e);
            return null;
        }
    }

    @Nullable
    private static String httpGet(String url, String sourceLabel, String playerName) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Allium-Plugin/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Text.sendDebugLog(WARN, sourceLabel + " returned HTTP " + responseCode + " for " + playerName);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "HTTP error while contacting " + sourceLabel + " for " + playerName, e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
        if (!player.hasPermission("allium.skull")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        // Check arguments
        if (args.length != 1) {
            player.sendMessage("§cUsage: /" + label + " <player>");
            return true;
        }

        final String targetName = args[0];

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

        // Handle player head - use Bukkit PlayerProfile API first (EssentialsX-style, no HTTP)
        player.sendMessage("§aFetching head for §e" + targetName + "§a...");

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                // 1. Bukkit PlayerProfile API (1.18.1+) - fetches from Mojang internally, no HTTP
                ItemStack skull = fetchPlayerHeadViaProfile(targetName);
                String source = "Bukkit Profile API";
                if (skull == null) {
                    skull = createPlayerHead(targetName);
                    source = "local player";
                }
                if (skull == null) {
                    skull = fetchPlayerHeadFromMojang(targetName);
                    source = "Mojang API";
                }
                if (skull == null) {
                    skull = fetchPlayerHeadFromMinecraftHeads(targetName);
                    source = "Minecraft-Heads API";
                }
                final ItemStack result = skull;
                final String resultSource = source;
                SchedulerAdapter.run(() -> {
                    if (result != null) {
                        giveSkullItem(player, result, targetName, resultSource);
                    } else {
                        player.sendMessage("§cCould not find a player with that name or failed to fetch head.");
                    }
                });

            } catch (Exception e) {
                Text.sendDebugLog(ERROR, "Error in skull command async task", e);
                SchedulerAdapter.run(() -> {
                    player.sendMessage("§cAn error occurred while fetching the head.");
                });
            } finally {
                executor.shutdown();
            }
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
            Text.sendDebugLog(WARN, "Error giving skull to player: " + e.getMessage());
            player.sendMessage("§cAn error occurred while giving you the head.");
        }
    }
}
