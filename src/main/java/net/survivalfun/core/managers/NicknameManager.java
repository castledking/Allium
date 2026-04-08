package net.survivalfun.core.managers;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import me.croabeast.prismatic.PrismaticAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NicknameManager {
    private static final LegacyComponentSerializer DISPLAY_NAME_SERIALIZER =
        LegacyComponentSerializer.builder().hexColors().character('§').build();

    private final PluginStart plugin;
    private final Database database;
    private final boolean tabPluginPresent;
    private FileConfiguration animationsConfig;
    private final Map<String, NicknameAnimation> animations = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /** In-memory nickname so placeholders (%allium_nickname%, etc.) see the current value immediately after set/reset. */
    private final Map<UUID, String> inMemoryNicknames = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, String>> playerLetterColors = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> playerGlowEffects = new ConcurrentHashMap<>();
    private int defaultCooldown;
    private int maxNickLength;
    private int minNickLength;
    private String allowedCharacters;
    private List<String> forbiddenNicks = new ArrayList<>();
    
    // Pre-built templates
    private final Map<String, String[]> templates = new HashMap<>();

    public NicknameManager(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        this.tabPluginPresent = plugin.getServer().getPluginManager().isPluginEnabled("TAB");
        loadConfig();
        loadTemplates();
    }

    private void loadTemplates() {
        templates.put("rainbow", new String[]{"&c&l", "&6&l", "&e&l", "&a&l", "&b&l", "&9&l", "&d&l"});
        templates.put("fire", new String[]{"&c", "&c&l", "&6", "&6&l", "&e", "&e&l"});
        templates.put("ocean", new String[]{"&1", "&1&l", "&9", "&9&l", "&b", "&b&l"});
        templates.put("forest", new String[]{"&2", "&2&l", "&a", "&a&l", "&e", "&e&l"});
        templates.put("galaxy", new String[]{"&5", "&d", "&9", "&1", "&5"});
        templates.put("helper", new String[]{"&a", "&b", "&c"});
        templates.put("owner", new String[]{"&c", "&6", "&e"});
        templates.put("admin", new String[]{"&c", "&4", "&c", "&4"});
        templates.put("mod", new String[]{"&2", "&a", "&2", "&a"});
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "animations.yml");
        if (!file.exists()) {
            plugin.saveResource("animations.yml", false);
        }
        
        animationsConfig = YamlConfiguration.loadConfiguration(file);
        loadAnimations();
        loadDefaults();
    }

    private void loadAnimations() {
        animations.clear();
        if (animationsConfig.contains("animations")) {
            for (String key : animationsConfig.getConfigurationSection("animations").getKeys(false)) {
                String path = "animations." + key;
                long frameDelay = animationsConfig.getLong(path + ".frame-delay", 5);
                List<String> frames = animationsConfig.getStringList(path + ".frames");
                String permission = animationsConfig.getString(path + ".permission", "");
                if (!frames.isEmpty()) {
                    animations.put(key.toLowerCase(), new NicknameAnimation(key, frames, frameDelay, permission));
                }
            }
        }
    }

    private void loadDefaults() {
        defaultCooldown = animationsConfig.getInt("defaults.cooldown", 60);
        maxNickLength = animationsConfig.getInt("defaults.max-nick-length", 20);
        minNickLength = animationsConfig.getInt("defaults.min-nick-length", 3);
        allowedCharacters = animationsConfig.getString("defaults.allowed-characters", 
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_");
    }

    public Map<String, NicknameAnimation> getAnimations() {
        return new HashMap<>(animations);
    }

    public boolean isOnCooldown(Player player) {
        if (player.hasPermission("allium.nick.nocooldown")) return false;
        return cooldowns.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    public long getRemainingCooldown(Player player) {
        long cooldownEnd = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        return Math.max(0, (cooldownEnd - System.currentTimeMillis()) / 1000);
    }

    public void setCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + (defaultCooldown * 1000L));
    }

    public boolean isValidNickname(String nickname) {
        if (nickname == null || nickname.length() < minNickLength || nickname.length() > maxNickLength) {
            return false;
        }
        // Check for forbidden terms
        for (String forbidden : forbiddenNicks) {
            if (nickname.toLowerCase().contains(forbidden.toLowerCase())) {
                return false;
            }
        }
        // Check if nickname matches any existing player name (like Essentials)
        if (isPlayerName(nickname)) {
            return false;
        }
        // Strip color codes and formatting for validation
        String stripped = stripColor(nickname);
        
        for (char c : stripped.toCharArray()) {
            if (allowedCharacters.indexOf(c) == -1) {
                return false;
            }
        }
        return true;
    }
    
    private String stripColor(String text) {
        if (text == null) return "";
        // Strip legacy & and § codes
        text = text.replaceAll("&[0-9a-fk-orA-FK-OR]", "").replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        // Strip MiniMessage tags (<red>, <gradient:...>, </gradient>, etc.) so validation checks only visible text
        text = text.replaceAll("<[^>]+>", "");
        return text;
    }
    
    /**
     * Check if a nickname matches an existing player's name (online or offline)
     * @param nickname The nickname to check
     * @return true if the nickname matches a player's name
     */
    public boolean isPlayerName(String nickname) {
        // Strip color codes for comparison
        String strippedNickname = PrismaticAPI.stripAll(nickname).toLowerCase();
        if (strippedNickname.isEmpty()) {
            return false;
        }
        
        // Check online players
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.getName().equalsIgnoreCase(strippedNickname)) {
                return true;
            }
        }
        
        // Check offline players via server
        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(strippedNickname);
            if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
                return true;
            }
        } catch (Exception e) {
            // Ignore lookup errors
        }
        
        return false;
    }
    
    public String formatNickname(Player player, String nickname) {
        if (nickname == null || nickname.isEmpty()) {
            return "";
        }
        try {
            // Support MiniMessage tags (<red>, <gradient:#x:#y>, <rainbow>) like lore/rename
            if (Text.detectColorFormat(nickname) == Text.ColorFormat.MINI_MESSAGE) {
                String result = Text.parseColors(nickname);
                return (result != null && !result.isEmpty()) ? result : nickname;
            }
            if (nickname.contains("&") || nickname.contains("§") || nickname.contains("#")) {
                String result = Text.parseColors(nickname);
                return (result != null && !result.isEmpty()) ? result : nickname;
            }
            String result = PrismaticAPI.colorize(player, nickname);
            return (result != null && !result.isEmpty()) ? result : nickname;
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error formatting nickname for " + (player != null ? player.getName() : "?") + ": " + e.getMessage());
            return nickname;
        }
    }

    /**
     * Gets the stored nickname for a player. Only returns DB value when explicitly set via GUI or /nick.
     * Returns player's default in-game name when not edited. Never returns null or empty when player is non-null.
     */
    public String getStoredNickname(Player player) {
        if (player == null) return "";
        String defaultName = player.getName();
        if (defaultName == null || defaultName.isEmpty()) {
            defaultName = player.getUniqueId().toString();
        }
        String inMem = inMemoryNicknames.get(player.getUniqueId());
        if (inMem != null && !inMem.trim().isEmpty()) return inMem;
        if (database == null) return defaultName;
        try {
            String dbNick = database.getStoredPlayerDisplayname(player.getUniqueId());
            if (dbNick != null && !dbNick.trim().isEmpty()) {
                return dbNick;
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error loading nickname from DB for " + player.getName() + ": " + e.getMessage());
        }
        return defaultName;
    }

    /**
     * Gets the stored nickname for an offline player by UUID (e.g. for PlaceholderAPI tab/scoreboard).
     * Returns the given defaultName when no nickname is stored.
     */
    public String getStoredNickname(UUID playerUUID, String defaultName) {
        if (playerUUID == null || defaultName == null) return (defaultName != null) ? defaultName : "";
        if (defaultName.isEmpty()) defaultName = playerUUID.toString();
        String inMem = inMemoryNicknames.get(playerUUID);
        if (inMem != null && !inMem.trim().isEmpty()) return inMem;
        if (database == null) return defaultName;
        try {
            String dbNick = database.getStoredPlayerDisplayname(playerUUID);
            if (dbNick != null && !dbNick.trim().isEmpty()) {
                return dbNick;
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error loading nickname from DB for " + playerUUID + ": " + e.getMessage());
        }
        return defaultName;
    }

    /**
     * Sets a player's nickname and updates their display name
     * @param player The player to set the nickname for
     * @param nickname The nickname to set (can contain color codes)
     * @return true if the nickname was set successfully, false otherwise
     */
    public boolean setNickname(Player player, String nickname) {
        if (player == null || nickname == null || nickname.isEmpty()) {
            return false;
        }

        // Validate the nickname
        if (!isValidNickname(nickname)) {
            return false;
        }

        try {
            // Format the nickname with colors and styles
            String formattedNick = formatNickname(player, nickname);

            // Update the player's display name (normalize & to § then deserialize so colors render)
            Component displayComponent = DISPLAY_NAME_SERIALIZER.deserialize(formattedNick.replace('&', '§'));
            applyDisplayIdentity(player, displayComponent);

            // So placeholders (%allium_nickname%, etc.) see the new value immediately
            inMemoryNicknames.put(player.getUniqueId(), nickname);

            // Persist to database (creates player_data row if needed so nickname survives restarts)
            if (database != null) {
                database.setStoredPlayerDisplayname(player.getUniqueId(), player.getName(), nickname);
            } else {
                Text.sendDebugLog(WARN, "Database not available; nickname for " + player.getName() + " applied in-session only and will not persist.");
            }

            // Set cooldown
            setCooldown(player);
            
            return true;
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error setting nickname for " + player.getName(), e);
            return false;
        }
    }
    
    public String applyGradient(String text, String startColor, String endColor) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        // Remove any existing color codes
        text = PrismaticAPI.stripAll(text);
        
        try {
            // Convert hex strings to Color objects
            java.awt.Color start = java.awt.Color.decode(startColor);
            java.awt.Color end = java.awt.Color.decode(endColor);
            
            // Apply gradient using PrismaticAPI with Color objects
            return PrismaticAPI.applyGradient(text, start, end, false);
        } catch (Exception e) {
            // Fallback to default color if parsing fails
            return text;
        }
    }

    public void resetNickname(Player player) {
        if (player == null) return;
        
        String playerName = player.getName();
        String formattedNick = formatNickname(player, playerName);
        Component displayComponent = DISPLAY_NAME_SERIALIZER.deserialize(formattedNick.replace('&', '§'));
        applyDisplayIdentity(player, displayComponent);
        inMemoryNicknames.put(player.getUniqueId(), playerName);
        if (database != null) {
            try {
                database.setStoredPlayerDisplayname(player.getUniqueId(), playerName, playerName);
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "Error resetting nickname in DB for " + player.getName() + ": " + e.getMessage());
            }
        }
        player.sendMessage(PrismaticAPI.colorize(player, "&aYour nickname has been reset to " + playerName));
    }

    /**
     * Restores a player's display name from stored nickname (e.g. on join).
     * Does not validate, set cooldown, or save to DB.
     */
    public void restoreDisplayNameFromStored(Player player, String storedNick) {
        if (player == null || storedNick == null || storedNick.trim().isEmpty()) return;
        try {
            inMemoryNicknames.put(player.getUniqueId(), storedNick);
            if (tabPluginPresent) {
                return;
            }
            String formatted = getFormattedNickname(player, storedNick);
            Component displayComponent = DISPLAY_NAME_SERIALIZER.deserialize(formatted.replace('&', '§'));
            applyDisplayIdentity(player, displayComponent);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error restoring nickname for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void applyDisplayIdentity(Player player, Component displayComponent) {
        if (tabPluginPresent) {
            // With TAB present, do not mutate Bukkit display state at all.
            // TAB, PlaceholderAPI and other formatting plugins should remain the sole owners of name presentation.
            return;
        }
        player.displayName(displayComponent);
        player.playerListName(displayComponent);
    }

    private void applyPlayerListName(Player player, Component displayComponent) {
        if (tabPluginPresent) {
            // TAB owns tablist formatting. Clear any plugin-set override so TAB can control the tab entry fully.
            player.playerListName(null);
            return;
        }
        player.playerListName(displayComponent);
    }

    public Map<String, String[]> getTemplates() {
        return templates;
    }

    public void setLetterColor(Player player, int index, String colorCode) {
        playerLetterColors.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(index, colorCode);
    }

    public void clearLetterColors(Player player) {
        playerLetterColors.remove(player.getUniqueId());
    }

    public Map<Integer, String> getLetterColors(Player player) {
        return playerLetterColors.getOrDefault(player.getUniqueId(), new HashMap<>());
    }

    public boolean hasLetterColors(Player player) {
        Map<Integer, String> colors = playerLetterColors.get(player.getUniqueId());
        return colors != null && !colors.isEmpty();
    }

    public void setGlowEffect(Player player, boolean glow) {
        playerGlowEffects.put(player.getUniqueId(), glow);
    }

    public boolean hasGlowEffect(Player player) {
        return playerGlowEffects.getOrDefault(player.getUniqueId(), false);
    }

    public String applyLetterColors(Player player, String nickname) {
        Map<Integer, String> letterColors = getLetterColors(player);
        if (letterColors.isEmpty()) {
            return nickname;
        }

        StringBuilder result = new StringBuilder();
        String[] letters = nickname.split("");
        
        for (int i = 0; i < letters.length; i++) {
            String color = letterColors.get(i);
            if (color != null && !color.isEmpty()) {
                // LetterColorGUI stores "a","b",etc. Prepend & for legacy; &#xxxxxx for hex
                String code = (color.length() == 1 && "0123456789abcdefABCDEF".indexOf(color.charAt(0)) >= 0)
                    ? "&" + color
                    : (color.startsWith("#") ? "&#" + color.substring(1) : color);
                result.append(code).append(letters[i]);
            } else {
                result.append(letters[i]);
            }
        }
        
        return result.toString();
    }

    public String applyTemplate(String templateName, String nickname) {
        String[] colors = templates.get(templateName.toLowerCase());
        if (colors == null) {
            return nickname;
        }

        StringBuilder result = new StringBuilder();
        String[] letters = nickname.split("");
        
        for (int i = 0; i < letters.length; i++) {
            String color = colors[i % colors.length];
            result.append(color).append(letters[i]);
        }
        
        return result.toString();
    }

    public String getFormattedNickname(Player player, String nickname) {
        String fallback = (player != null) ? (player.getName() != null && !player.getName().isEmpty() ? player.getName() : player.getUniqueId().toString()) : "";
        if (nickname == null || nickname.isEmpty()) {
            return fallback;
        }
        String formatted = formatNickname(player, nickname);
        if (formatted == null || formatted.isEmpty()) {
            return fallback;
        }
        if (hasLetterColors(player)) {
            formatted = applyLetterColors(player, formatted);
        }
        if (hasGlowEffect(player)) {
            formatted = "§k" + formatted + "§r";
        }
        // Final safety: never return blank when we have a valid player
        return (formatted != null && !formatted.trim().isEmpty()) ? formatted : fallback;
    }

    public int getMaxNickLength() {
        return maxNickLength;
    }

    public int getMinNickLength() {
        return minNickLength;
    }

    public static class NicknameAnimation {
        private final List<String> frames;
        private int currentFrame = 0;
        private long lastUpdate = 0;
        private final long frameDelay;
        private final String name;
        private final String permission;

        public NicknameAnimation(String name, List<String> frames, long frameDelay, String permission) {
            this.name = name;
            this.frames = frames.stream()
                .map(frame -> frame.replace("&", "§")) // Convert & to § for compatibility
                .collect(Collectors.toList());
            this.frameDelay = frameDelay * 50; // Convert to milliseconds
            this.permission = permission;
        }

        public String getCurrentFrame() {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUpdate >= frameDelay) {
                currentFrame = (currentFrame + 1) % frames.size();
                lastUpdate = currentTime;
            }
            return frames.get(currentFrame);
        }
        
        public String getName() {
            return name;
        }
        
        public String getPermission() {
            return permission;
        }
        
        public boolean hasPermission(Player player) {
            return permission == null || permission.isEmpty() || player.hasPermission(permission);
        }
    }
}
