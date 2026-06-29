package codes.castled.allium.managers.security;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * ModGuard-style mod detection for Allium.
 * Replaces PacketEvents-based detection with Bukkit PluginMessageListener.
 * Detects and kicks players using banned client-side mods.
 */
public class ModGuardManager implements PluginMessageListener, Listener {

    private final PluginStart plugin;
    private FileConfiguration config;
    private ModGuardTranslationProbe translationProbe;
    private final Map<UUID, Set<String>> playerDetectedMods;
    private final Map<UUID, String> playerBrands;
    private final Map<UUID, String> playerLogSessions;
    private final Set<UUID> processedPlayers;

    public ModGuardManager(PluginStart plugin) {
        this.plugin = plugin;
        this.playerDetectedMods = new HashMap<>();
        this.playerBrands = new HashMap<>();
        this.playerLogSessions = new HashMap<>();
        this.processedPlayers = new HashSet<>();

        plugin.getLogger().info("[ModGuard] === CONSTRUCTOR CALLED ===");
        plugin.getLogger().info("[ModGuard] Plugin instance: " + plugin);
        plugin.getLogger().info("[ModGuard] Server: " + Bukkit.getServer());
        plugin.getLogger().info("[ModGuard] PluginManager: " + Bukkit.getPluginManager());

        loadConfig();
        registerPluginChannels();
        registerTranslationProbe();
        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("[ModGuard] === CONSTRUCTOR COMPLETE ===");
    }

    private void registerTranslationProbe() {
        if (!config.getBoolean("translation-probe.enabled", false)) {
            return;
        }
        if (!codes.castled.allium.packetevents.PacketEventsLoader.isPacketEventsAvailable()) {
            plugin.getLogger().warning("[ModGuard] Translation probe enabled but PacketEvents is unavailable.");
            return;
        }
        try {
            translationProbe = new ModGuardTranslationProbe(plugin, this);
            translationProbe.register();
            plugin.getLogger().info("[ModGuard] Translation probe registered.");
        } catch (Throwable t) {
            plugin.getLogger().warning("[ModGuard] Failed to register translation probe: " + t.getMessage());
        }
    }

    private void loadConfig() {
        File modGuardFolder = new File(plugin.getDataFolder(), "modguard");
        if (!modGuardFolder.exists() && !modGuardFolder.mkdirs()) {
            plugin.getLogger().warning("[ModGuard] Could not create modguard config folder.");
        }

        File configFile = new File(modGuardFolder, "config.yml");
        if (!configFile.exists()) {
            migrateOrCreateConfig(configFile);
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        applyBundledDefaults(configFile);
    }

    private void migrateOrCreateConfig(File configFile) {
        ConfigurationSection legacySection = plugin.getConfig().getConfigurationSection("modguard");
        if (legacySection != null) {
            YamlConfiguration migrated = new YamlConfiguration();
            copySection(legacySection, migrated);
            try {
                migrated.save(configFile);
                plugin.getLogger().info("[ModGuard] Migrated root config.yml modguard section to modguard/config.yml");
                return;
            } catch (IOException e) {
                plugin.getLogger().warning("[ModGuard] Failed to migrate modguard config: " + e.getMessage());
            }
        }

        try {
            plugin.saveResource("modguard/config.yml", false);
            plugin.getLogger().info("[ModGuard] Created modguard/config.yml from bundled defaults");
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[ModGuard] Bundled modguard/config.yml missing, creating minimal config.");
            YamlConfiguration fallback = new YamlConfiguration();
            fallback.set("enabled", true);
            fallback.set("mode", "blacklist");
            fallback.set("check-on-join", true);
            fallback.set("kick-on-detection", true);
            try {
                fallback.save(configFile);
            } catch (IOException ioException) {
                plugin.getLogger().warning("[ModGuard] Failed to create minimal config: " + ioException.getMessage());
            }
        }
    }

    private void applyBundledDefaults(File configFile) {
        try (var stream = plugin.getResource("modguard/config.yml")) {
            if (stream == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[ModGuard] Failed to apply default config values: " + e.getMessage());
        }
    }

    private void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (value instanceof ConfigurationSection childSection) {
                ConfigurationSection targetChild = target.createSection(key);
                copySection(childSection, targetChild);
            } else {
                target.set(key, value);
            }
        }
    }

    private void registerPluginChannels() {
        var messenger = Bukkit.getMessenger();
        if (messenger == null) {
            plugin.getLogger().severe("[ModGuard] Messenger is null! Cannot register channels.");
            return;
        }

        plugin.getLogger().info("[ModGuard] Registering plugin channels...");

        // Modern Paper 1.21+ compatible channels
        // Note: minecraft:register is reserved in modern Paper
        String[] channels = {
                "minecraft:brand",
                "fml:handshake",
                "fml:login",
                "forge:handshake",
                "forge:login"
        };

        for (String channel : channels) {
            try {
                messenger.registerIncomingPluginChannel(plugin, channel, this);
                plugin.getLogger().info("[ModGuard] Registered channel: " + channel);
            } catch (Exception e) {
                plugin.getLogger().warning("[ModGuard] Cannot register " + channel + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("[ModGuard] Channel registration complete");
    }

    public void shutdown() {
        if (translationProbe != null) {
            translationProbe.unregister();
            translationProbe = null;
        }
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin);
        playerDetectedMods.clear();
        playerBrands.clear();
        playerLogSessions.clear();
        processedPlayers.clear();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        plugin.getLogger().info("[ModGuard] Plugin message from " + player.getName() + " on channel: " + channel + " (enabled=" + isEnabled() + ")");

        if (!isEnabled()) {
            return;
        }

        if (player.hasPermission(getBypassPermission())) {
            plugin.getLogger().info("[ModGuard] Player " + player.getName() + " has bypass, skipping plugin message");
            return;
        }

        plugin.getLogger().info("[ModGuard] Processing plugin message on channel: " + channel + " from " + player.getName());

        switch (channel.toLowerCase()) {
            case "minecraft:brand":
                if (isCheckBrand()) {
                    handleBrandMessage(player, data);
                }
                break;
            case "fml:handshake":
            case "fml:login":
            case "forge:handshake":
            case "forge:login":
                if (isCheckFmlHandshake()) {
                    handleFMLHandshake(player, data);
                }
                break;
        }
    }

    private void handleBrandMessage(Player player, byte[] data) {
        try {
            String brand = new String(data, StandardCharsets.UTF_8);

            if (brand.startsWith("\u0000")) {
                brand = brand.substring(1);
            }

            int length = data[0] & 0xFF;
            if (length > 0 && length < data.length) {
                brand = new String(data, 1, length, StandardCharsets.UTF_8);
            }

            playerBrands.put(player.getUniqueId(), brand);

            if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Brand for " + player.getName() + ": " + brand);
            }

            // Check for mod loaders
            if (brand.toLowerCase().contains("fabric") || brand.toLowerCase().contains("forge") ||
                    brand.toLowerCase().contains("quilt") || brand.toLowerCase().contains("neoforge")) {
                addDetectedMod(player, "modloader:" + brand.toLowerCase());
            }

        } catch (Exception e) {
            if (isDebug()) {
                plugin.getLogger().warning("[ModGuard] Error parsing brand message: " + e.getMessage());
            }
        }
    }

    private void handleRegisterMessage(Player player, byte[] data) {
        try {
            String channels = new String(data, StandardCharsets.UTF_8);
            String[] channelList = channels.split("\u0000");

            for (String registeredChannel : channelList) {
                if (registeredChannel.isEmpty()) {
                    continue;
                }

                if (isDebug()) {
                    plugin.getLogger().info("[ModGuard] Channel registered by " + player.getName() + ": " + registeredChannel);
                }

                String channelLower = registeredChannel.toLowerCase();

                // Check for known mod channels
                if (channelLower.contains("fabric") || channelLower.contains("forge") ||
                        channelLower.contains("quilt") || channelLower.contains("litematic") ||
                        channelLower.contains("tweaker") || channelLower.contains("freecam") ||
                        channelLower.contains("minihud") || channelLower.contains("meteor") ||
                        channelLower.contains("wurst") || channelLower.contains("vape") ||
                        channelLower.contains("liquid") || channelLower.contains("inventory-profiles") ||
                        channelLower.contains("inventoryprofiles")) {
                    addDetectedMod(player, registeredChannel);
                }

                // Extract mod ID from channel namespace
                String modId = extractModIdFromChannel(registeredChannel);
                if (modId != null) {
                    addDetectedMod(player, modId);
                }
            }

        } catch (Exception e) {
            if (isDebug()) {
                plugin.getLogger().warning("[ModGuard] Error parsing register message: " + e.getMessage());
            }
        }
    }

    private void handleFMLHandshake(Player player, byte[] data) {
        try {
            String dataStr = new String(data, StandardCharsets.UTF_8);

            if (isDebug()) {
                plugin.getLogger().info("[ModGuard] FML handshake from " + player.getName() + " (length: " + data.length + ")");
            }

            if (dataStr.contains("FML") || dataStr.contains("forge")) {
                addDetectedMod(player, "forge-modloader");
            }

            String[] parts = dataStr.split("\u0000");
            for (String part : parts) {
                if (part.length() > 2 && part.length() < 64) {
                    String partLower = part.toLowerCase();
                    if (partLower.matches("[a-z0-9_-]+")) {
                        addDetectedMod(player, part);
                    }
                }
            }

        } catch (Exception e) {
            if (isDebug()) {
                plugin.getLogger().warning("[ModGuard] Error parsing FML handshake: " + e.getMessage());
            }
        }
    }

    private String extractModIdFromChannel(String channel) {
        if (channel.contains(":")) {
            String[] parts = channel.split(":", 2);
            String namespace = parts[0];

            if (!namespace.equals("minecraft") && !namespace.equals("bukkit") &&
                    !namespace.equals("spigot") && !namespace.equals("paper")) {
                return namespace;
            }
        }
        return null;
    }

    private void addDetectedMod(Player player, String modId) {
        playerDetectedMods.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(modId);
        plugin.getLogger().info("[ModGuard] Detected mod for " + player.getName() + ": " + modId);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playerLogSessions.put(player.getUniqueId(), Instant.now().toString().replace(":", "-"));

        plugin.getLogger().info("[ModGuard] Player join: " + player.getName() + " (enabled=" + isEnabled() + ", checkOnJoin=" + isCheckOnJoin() + ")");

        if (!isEnabled() || !isCheckOnJoin()) {
            plugin.getLogger().info("[ModGuard] Skipping check - disabled or check-on-join false");
            return;
        }

        if (player.hasPermission(getBypassPermission())) {
            plugin.getLogger().info("[ModGuard] Player " + player.getName() + " has bypass permission, skipping");
            return;
        }

        SchedulerAdapter.runLater(() -> {
            if (!player.isOnline()) {
                cleanup(player);
                return;
            }

            // Prevent duplicate processing
            if (processedPlayers.contains(player.getUniqueId())) {
                return;
            }

            Set<String> detectedMods = playerDetectedMods.getOrDefault(player.getUniqueId(), new HashSet<>());
            String brand = playerBrands.getOrDefault(player.getUniqueId(), "unknown");

            // Fallback: check brand via Bukkit API if not detected via plugin messages
            if (brand.equals("unknown") || detectedMods.isEmpty()) {
                try {
                    String clientBrand = player.getClientBrandName();
                    if (clientBrand != null && !clientBrand.isEmpty()) {
                        brand = clientBrand;
                        playerBrands.put(player.getUniqueId(), brand);
                        plugin.getLogger().info("[ModGuard] Got brand via API: " + brand);

                        // Check for mod loaders in brand
                        if (brand.toLowerCase().contains("fabric")) {
                            addDetectedMod(player, "modloader:fabric");
                        } else if (brand.toLowerCase().contains("forge")) {
                            addDetectedMod(player, "modloader:forge");
                        } else if (brand.toLowerCase().contains("quilt")) {
                            addDetectedMod(player, "modloader:quilt");
                        } else if (brand.toLowerCase().contains("neoforge")) {
                            addDetectedMod(player, "modloader:neoforge");
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[ModGuard] Failed to get client brand: " + e.getMessage());
                }
            }

            // Re-fetch after potential fallback
            detectedMods = playerDetectedMods.getOrDefault(player.getUniqueId(), new HashSet<>());
            brand = playerBrands.getOrDefault(player.getUniqueId(), "unknown");

            plugin.getLogger().info("[ModGuard] Checking " + player.getName() + " - detected mods: " + detectedMods.size() + ", brand: " + brand);

            if (isLogAllMods() && !detectedMods.isEmpty()) {
                String modsStr = String.join(", ", detectedMods);
                plugin.getLogger().info(getDetectionLog()
                        .replace("{player}", player.getName())
                        .replace("{mods}", modsStr)
                        .replace("{brand}", brand));
            }

            // Check for banned mods
            for (String modId : detectedMods) {
                if (isModBanned(modId)) {
                    handleBannedMod(player, modId, brand);
                    processedPlayers.add(player.getUniqueId());
                    return;
                }
            }

            // Whitelist mode check
            if (isWhitelistMode()) {
                for (String modId : detectedMods) {
                    if (!isModAllowed(modId) && !modId.startsWith("modloader:")) {
                        handleBannedMod(player, modId, brand);
                        processedPlayers.add(player.getUniqueId());
                        return;
                    }
                }
            }

            processedPlayers.add(player.getUniqueId());

        }, getCheckDelayTicks());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer());
    }

    private void handleBannedMod(Player player, String modId, String brand) {
        logHit(player, "plugin-message", modId, modId, "corroborated", isKickOnDetection() ? "kick" : "alert", isKickOnDetection());

        if (isKickOnDetection()) {
            kickPlayer(player, modId);
        }

        if (isLogDetections()) {
            plugin.getLogger().warning("[ModGuard] Player " + player.getName() + " detected using banned mod: " + modId + " (brand: " + brand + ")");
        }

        if (isNotifyStaff()) {
            notifyStaff(player, modId);
        }
    }

    private boolean isModBanned(String modId) {
        String checkId = isCaseSensitive() ? modId : modId.toLowerCase();

        for (String bannedMod : getBannedMods()) {
            String checkBanned = isCaseSensitive() ? bannedMod : bannedMod.toLowerCase();

            if (isRegexMatching()) {
                try {
                    Pattern pattern = Pattern.compile(checkBanned, isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE);
                    if (pattern.matcher(checkId).find()) {
                        return true;
                    }
                } catch (Exception e) {
                    if (checkId.contains(checkBanned)) {
                        return true;
                    }
                }
            } else {
                if (checkId.contains(checkBanned) || checkId.equals(checkBanned)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isModAllowed(String modId) {
        String checkId = isCaseSensitive() ? modId : modId.toLowerCase();

        for (String allowedMod : getAllowedMods()) {
            String checkAllowed = isCaseSensitive() ? allowedMod : allowedMod.toLowerCase();
            if (checkId.contains(checkAllowed) || checkId.equals(checkAllowed)) {
                return true;
            }
        }

        return false;
    }

    private void kickPlayer(Player player, String modId) {
        String message = getKickMessage()
                .replace("{mod}", modId)
                .replace("{player}", player.getName());

        net.kyori.adventure.text.Component kickMessage = codes.castled.allium.managers.core.Text.colorize(message);
        player.kick(kickMessage);
    }

    private void notifyStaff(Player player, String modId) {
        String message = getStaffAlert()
                .replace("{player}", player.getName())
                .replace("{mod}", modId);

        String staffPermission = getStaffPermission();
        codes.castled.allium.managers.core.SecurityAlertManager alerts = plugin.getSecurityAlertManager();
        if (alerts != null) {
            alerts.broadcastModGuardAlert(player, message, staffPermission);
            return;
        }

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(staffPermission)) {
                staff.sendMessage(codes.castled.allium.managers.core.Text.parseColors(message));
            }
        }
    }

    void handleTranslationProbeHit(Player player, String checkId, String key, String resolved, String fallback, String configuredAction, boolean requireCorroboration) {
        Set<String> knownSignals = playerDetectedMods.getOrDefault(player.getUniqueId(), Collections.emptySet());
        boolean corroborated = !knownSignals.isEmpty();
        String confidence = corroborated ? "corroborated" : "translation-only";
        String action = normalizeAction(configuredAction, "alert");

        if ("kick".equals(action) && requireCorroboration && !corroborated) {
            action = "alert";
        }

        boolean kicked = "kick".equals(action);
        logHit(player, "translation-probe", checkId,
                "key=" + key + ", resolved=" + resolved + ", fallback=" + fallback,
                confidence, action, kicked);

        if ("none".equals(action)) {
            return;
        }

        String message = getTranslationProbeAlert()
                .replace("{player}", player.getName())
                .replace("{check}", checkId)
                .replace("{key}", key)
                .replace("{resolved}", resolved)
                .replace("{confidence}", confidence)
                .replace("{action}", action);

        codes.castled.allium.managers.core.SecurityAlertManager alerts = plugin.getSecurityAlertManager();
        if (alerts != null) {
            alerts.broadcastModGuardAlert(player, message, getStaffPermission());
        } else {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(getStaffPermission())) {
                    staff.sendMessage(codes.castled.allium.managers.core.Text.parseColors(message));
                }
            }
        }

        if (kicked) {
            kickPlayer(player, checkId);
        }
    }

    private String normalizeAction(String action, String fallback) {
        if (action == null || action.isBlank()) {
            return fallback;
        }
        String normalized = action.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "alert", "kick" -> normalized;
            default -> fallback;
        };
    }

    private void logHit(Player player, String method, String checkId, String raw, String confidence, String action, boolean punished) {
        try {
            File logsFolder = new File(plugin.getDataFolder(), "modguard/logs");
            if (!logsFolder.exists() && !logsFolder.mkdirs()) {
                plugin.getLogger().warning("[ModGuard] Could not create logs folder.");
                return;
            }

            File logFile = new File(logsFolder, player.getUniqueId() + ".yml");
            YamlConfiguration log = YamlConfiguration.loadConfiguration(logFile);
            long now = System.currentTimeMillis();
            if (!log.contains("first-hit")) {
                log.set("first-hit", now);
            }
            log.set("uuid", player.getUniqueId().toString());
            log.set("last-known-name", player.getName());
            log.set("last-hit", now);

            String sessionKey = playerLogSessions.computeIfAbsent(player.getUniqueId(), uuid -> Instant.now().toString().replace(":", "-"));
            String sessionPath = "sessions." + sessionKey;
            if (!log.contains(sessionPath + ".started")) {
                log.set(sessionPath + ".started", Instant.now().toString());
                log.set(sessionPath + ".brand", playerBrands.getOrDefault(player.getUniqueId(), "unknown"));
            }

            List<Map<String, Object>> hits = new ArrayList<>();
            for (Object existing : log.getList(sessionPath + ".hits", Collections.emptyList())) {
                if (existing instanceof Map<?, ?> existingMap) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : existingMap.entrySet()) {
                        copy.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    hits.add(copy);
                }
            }

            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("time", Instant.now().toString());
            hit.put("method", method);
            hit.put("check", checkId);
            hit.put("raw", raw);
            hit.put("confidence", confidence);
            hit.put("action", action);
            hit.put("punished", punished);
            hits.add(hit);
            log.set(sessionPath + ".hits", hits);
            log.save(logFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[ModGuard] Failed to write evidence log for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void cleanup(Player player) {
        UUID uuid = player.getUniqueId();
        playerDetectedMods.remove(uuid);
        playerBrands.remove(uuid);
        playerLogSessions.remove(uuid);
        processedPlayers.remove(uuid);
    }

    // Config accessors
    private boolean isEnabled() {
        return config.getBoolean("enabled", true);
    }

    private boolean isDebug() {
        return config.getBoolean("debug", false);
    }

    private String getMode() {
        return config.getString("mode", "blacklist");
    }

    private boolean isWhitelistMode() {
        return "whitelist".equalsIgnoreCase(getMode());
    }

    private boolean isCheckOnJoin() {
        return config.getBoolean("check-on-join", true);
    }

    private boolean isKickOnDetection() {
        return config.getBoolean("kick-on-detection", true);
    }

    private boolean isCheckBrand() {
        return config.getBoolean("check-brand", true);
    }

    private boolean isCheckChannels() {
        return config.getBoolean("check-channels", true);
    }

    private boolean isCheckFmlHandshake() {
        return config.getBoolean("check-fml-handshake", true);
    }

    private boolean isLogDetections() {
        return config.getBoolean("log-detections", true);
    }

    private boolean isLogAllMods() {
        return config.getBoolean("log-all-mods", false);
    }

    private boolean isNotifyStaff() {
        return config.getBoolean("notify-staff", true);
    }

    private boolean isRegexMatching() {
        return config.getBoolean("regex-matching", false);
    }

    private boolean isCaseSensitive() {
        return config.getBoolean("case-sensitive", false);
    }

    private long getCheckDelayTicks() {
        return config.getLong("check-delay-ticks", 40);
    }

    private String getBypassPermission() {
        return config.getString("bypass-permission", "modguard.bypass");
    }

    private String getStaffPermission() {
        return config.getString("staff-permission", "modguard.notify");
    }

    private String getKickMessage() {
        return config.getString("kick-message",
                "&c&lMODIFIED CLIENT DETECTED\n\n&7You have been kicked from the server.\n&7Reason: &cBanned modification detected\n\n&7Detected mod: &e{mod}\n\n&7Please remove this mod and rejoin.");
    }

    private String getStaffAlert() {
        return config.getString("staff-alert",
                "&7[&c&lModGuard&7] &e{player} &7was kicked for using: &c{mod}");
    }

    private String getDetectionLog() {
        return config.getString("detection-log",
                "[ModGuard] Detected mods for {player}: {mods} (brand: {brand})");
    }

    private List<String> getBannedMods() {
        return config.getStringList("banned-mods");
    }

    private List<String> getAllowedMods() {
        return config.getStringList("allowed-mods");
    }

    FileConfiguration getModGuardConfig() {
        return config;
    }

    private String getTranslationProbeAlert() {
        return config.getString("translation-probe.alert-message",
                "&7[&cModGuard&7] &e{player} &7hit translation probe &c{check} &8(&f{key}&8) &7resolved=&f{resolved} &7confidence=&e{confidence}&7 action=&e{action}");
    }
}
