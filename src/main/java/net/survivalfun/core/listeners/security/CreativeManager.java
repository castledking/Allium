package net.survivalfun.core.listeners.security;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.DB.PlayerInventories;
import net.survivalfun.core.managers.core.Text;

public class CreativeManager implements Listener {

    private final PluginStart plugin;
    private final Database database;
    private final Set<Material> badBlocks;
    private final Set<EntityType> badEntities;
    private final Map<UUID, Long> lastErrorMessageTime;
    private final Map<UUID, GameMode> lastKnownGamemodes = new HashMap<>();
    private BukkitTask gamemodeCheckTask;
    private final Map<UUID, Long> recentRestorations = new HashMap<>();
    private static final long RESTORATION_COOLDOWN = 5000;
    private final Set<UUID> processedJoinEvents = ConcurrentHashMap.newKeySet();

    public CreativeManager(@NotNull PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        this.badBlocks = new HashSet<>();
        this.badEntities = new HashSet<>();
        this.lastErrorMessageTime = new HashMap<>();
        loadBlacklists();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[InventoryDebug] CreativeManager initialized with inventory change listener");
    }

    private boolean isOnErrorCooldown(Player player){
        UUID playerUUID = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        if (lastErrorMessageTime.containsKey(playerUUID)) {
            long lastErrorTime = lastErrorMessageTime.get(playerUUID);
            if (currentTime - lastErrorTime < 10000) {
                return true;
            }
        }
        lastErrorMessageTime.put(playerUUID, currentTime);
        return false;
    }

    private void sendErrorMessageWithCooldown(Player player, String messageKey,
                                              net.survivalfun.core.managers.lang.Lang lang,
                                              Object... replacements) {
        if (!isOnErrorCooldown(player)) {
            Text.sendErrorMessage(player, messageKey, lang, replacements);
        }
    }

    private void loadBlacklists() {
        List<String> blocks = plugin.getConfig().getStringList("creative-manager.blacklist.blocks");
        for (String blockName : blocks) {
            try {
                Material material = Material.valueOf(blockName.toUpperCase());
                badBlocks.add(material);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid block name in creative-mode.blacklist.blocks: " + blockName);
            }
        }

        List<String> entities = plugin.getConfig().getStringList("creative-manager.blacklist.entities");
        for (String entityName : entities) {
            try {
                EntityType entityType = EntityType.valueOf(entityName.toUpperCase());
                badEntities.add(entityType);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid entity name in creative-mode.blacklist.entities: " + entityName);
            }
        }
    }

    public void reloadBlacklists(){
        badBlocks.clear();
        badEntities.clear();
        loadBlacklists();
    }

    private final Map<UUID, Boolean> cachedCreativePermissions = new HashMap<>();
    private final Map<UUID, Boolean> cachedSpectatorPermissions = new HashMap<>();

    private void cachePlayerPermissions(Player player) {
        UUID uuid = player.getUniqueId();
        if (player.hasPermission("core.gamemode")) {
            cachedCreativePermissions.put(uuid, player.hasPermission("core.gamemode.creative"));
            cachedSpectatorPermissions.put(uuid, player.hasPermission("core.gamemode.spectator"));
            if (plugin.getConfig().getBoolean("debug-mode")) {
                plugin.getLogger().info("Cached permissions for " + player.getName() +
                        ": creative=" + cachedCreativePermissions.get(uuid) +
                        ", spectator=" + cachedSpectatorPermissions.get(uuid));
            }
        }
    }

    private boolean canPlace(Player player) {
        return player.hasPermission("core.gamemode.creative.place");
    }
    private boolean canBreak(Player player) {
        return player.hasPermission("core.gamemode.creative.break");
    }
    private boolean canBypassBlacklist(Player player) {
        return player.hasPermission("core.gamemode.creative.blacklist");
    }
    private boolean canSpawn(Player player) {
        return player.hasPermission("core.gamemode.creative.spawn");
    }
    private boolean canInteract(Player player) {
        return player.hasPermission("core.gamemode.creative.interact");
    }
    private boolean canDrop(Player player) {
        return player.hasPermission("core.gamemode.creative.drop");
    }
    private boolean canUse(Player player) {
        return player.hasPermission("core.gamemode.creative.use");
    }

    private void createFizzleEffect(Location location) {
        Particle.DustOptions dustOptions = new Particle.DustOptions(Color.RED, 1.0f);
        location.getWorld().spawnParticle(
                Particle.DUST,
                location,
                15,
                0.5,
                0.5,
                0.5,
                0,
                dustOptions
        );
        location.getWorld().playSound(
                location,
                Sound.BLOCK_FIRE_EXTINGUISH,
                SoundCategory.BLOCKS,
                0.5f,
                1.2f
        );
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractBed(PlayerInteractEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;
        if (event.getClickedBlock().getType().name().endsWith("_BED") && event.getAction().isRightClick()) return;

        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "sleep in a bed");
        createFizzleEffect(player.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractDecoratedPot(PlayerInteractEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;
        if (event.getClickedBlock().getType() == Material.DECORATED_POT && event.getAction().isRightClick()) return;

        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "interact with decorated pots");
        createFizzleEffect(player.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDrinkPotion(PlayerInteractEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;
        if (event.getItem().getType() == Material.POTION && event.getAction().isRightClick()) return;

        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "drink that");
        createFizzleEffect(player.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;

        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "shoot that");
        createFizzleEffect(player.getEyeLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerUseFirework(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getAction().isRightClick() && event.hasItem() && event.getItem().getType() == Material.FIREWORK_ROCKET) {
            if (canInteract(player)) return;

            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "use fireworks");
            createFizzleEffect(player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (!canInteract(player)) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "break that");
            createFizzleEffect(player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerRotateItemFrame(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getRightClicked() instanceof ItemFrame) {
            if (!canInteract(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "rotate items in frames"
                );
                createFizzleEffect(player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPaintingPlaceCreative(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getEntity() instanceof Painting) {
            if (!canInteract(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "place paintings"
                );
                createFizzleEffect(player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStandInteractCreative(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getRightClicked().getType() == EntityType.ARMOR_STAND) {
            if (!canInteract(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "interact with armor stands"
                );
                createFizzleEffect(player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlowerPotInteractCreative(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.FLOWER_POT) {
            if (!canInteract(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "interact with flower pots"
                );
                createFizzleEffect(player.getLocation());
            }
        }
    }

    public void onPlayerCastFishingRod(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            if (canInteract(player)) return;
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "use fishing rods");
            createFizzleEffect(player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;
        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw potions");
        createFizzleEffect(player.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getEntityType() == EntityType.TRIDENT) {
            if (canInteract(player)) return;
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw tridents");
            createFizzleEffect(player.getLocation());
        }
        if (event.getEntityType() == EntityType.ENDER_PEARL) {
            if (canInteract(player)) return;
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "throw ender pearls");
            createFizzleEffect(player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreativeInventoryManagement(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        Inventory clickedInventory = event.getClickedInventory();
        ItemStack itemOnCursor = player.getItemOnCursor();
        if (event.getSlot() == -999 && clickedInventory != null && clickedInventory.getType() == InventoryType.CREATIVE) {
            if (plugin.isDebugMode()) {
                plugin.getLogger().info(
                    "Allowed item destruction via X slot for " + player.getName() + 
                    ", item: " + itemOnCursor.getType()
                );
            }
            if (itemOnCursor != null && itemOnCursor.getType() != Material.AIR) {
                return;
            }
        }
        if (clickedInventory != null && clickedInventory.getType() == InventoryType.CREATIVE &&
            (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR)) {
            if (itemOnCursor != null && itemOnCursor.getType() != Material.AIR) {
                return;
            }
        }
        if (!canUse(player)) {
            event.setCancelled(true);
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                player.setItemOnCursor(null);
                player.updateInventory();
            });
            sendErrorMessageWithCooldown(
                    player,
                    "creative-manager.restrict",
                    plugin.getLangManager(),
                    "{action}", "manage your inventory"
            );
            createFizzleEffect(player.getLocation());
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerEquipArmorCreative(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            if (event.getClickedInventory() != null && event.getClickedInventory().getType() == InventoryType.PLAYER) {
                if (canInteract(player)) return;
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "equip armor");
                createFizzleEffect(player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntityCreative(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canInteract(player)) return;
        if (event.getRightClicked() instanceof org.bukkit.entity.LivingEntity) {
            event.setCancelled(true);
            String entityName = event.getRightClicked().getType().name().toLowerCase().replace("_", " ");
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(), "{action}", "interact with that " + entityName);
            createFizzleEffect(event.getRightClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamageEntity(EntityDamageByEntityEvent event) {
        if(event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            if (player.getGameMode() != GameMode.CREATIVE) return;
            if (canInteract(player)) return;
            String entityName = event.getEntity().getType().name().toLowerCase().replace("_", " ");
            event.setCancelled(true);
            sendErrorMessageWithCooldown(
                    player,
                    "creative-manager.restrict",
                    plugin.getLangManager(),
                    "{action}", "hurt that " + entityName
            );
            createFizzleEffect(event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerSpawnEntity(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!event.hasItem()) return;
        ItemStack item = event.getItem();
        if (item != null && item.getType().name().endsWith("_SPAWN_EGG")) {
            if (player.getGameMode() == GameMode.CREATIVE && !canSpawn(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "spawn that"
                );
                createFizzleEffect(player.getLocation());
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.getNearbyEntities(3, 3, 3).forEach(entity -> {
                    if (entity.getTicksLived() < 5) {
                        entity.setMetadata("spawned-by-player",
                                new org.bukkit.metadata.FixedMetadataValue(plugin, player));
                    }
                });
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");
        if (!processedJoinEvents.add(playerId)) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Duplicate join event ignored for " + player.getName());
            }
            return;
        }

        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Pre-join inventory for " + player.getName() +
                    ": " + Arrays.toString(player.getInventory().getContents()));
        }

        cachePlayerPermissions(player);
        lastKnownGamemodes.put(playerId, player.getGameMode());
        checkAndApplyGameModeReset(player);

        if (player.hasPermission("core.gamemode.creative.inventory")) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Skipping inventory management for " + player.getName() +
                        " in " + player.getGameMode() + " due to core.gamemode.creative.inventory permission");
            }
            processedJoinEvents.remove(playerId);
            return;
        }

        GameMode gameMode = player.getGameMode();
        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Loading " + gameMode + " inventory for " + player.getName());
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            loadInventory(player, gameMode);
            processedJoinEvents.remove(playerId);
        }, 20L);

        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Post-join inventory for " + player.getName() +
                    ": " + Arrays.toString(player.getInventory().getContents()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");

        cachedCreativePermissions.remove(playerId);
        cachedSpectatorPermissions.remove(playerId);
        lastKnownGamemodes.remove(playerId);

        if (player.hasPermission("core.gamemode.creative.inventory")) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Skipping inventory save for " + player.getName() +
                        " due to core.gamemode.creative.inventory permission");
            }
            return;
        }

        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Saving inventory for " + player.getName() +
                    " in " + player.getGameMode());
        }
        saveCurrentInventory(player, player.getGameMode());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChangeTrack(PlayerGameModeChangeEvent event) {
        Plugin sourcePlugin = detectSourcePlugin();
        if (sourcePlugin == null || !sourcePlugin.getName().equals("Multiverse-Core")) {
            lastKnownGamemodes.put(event.getPlayer().getUniqueId(), event.getNewGameMode());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World fromWorld = event.getFrom();
        World toWorld = player.getWorld();
        GameMode currentGamemode = player.getGameMode();
        UUID playerUUID = player.getUniqueId();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");

        if (debug) {
            plugin.getLogger().info("[InventoryDebug] " + player.getName() + " changed from world '" + fromWorld.getName() +
                    "' to '" + toWorld.getName() + "' with gamemode " + currentGamemode);
        }

        if (player.hasPermission("mv.bypass.gamemode." + toWorld.getName()) ||
                player.hasPermission("mv.bypass.gamemode.*") ||
                player.hasPermission("core.gamemode.creative") ||
                player.hasPermission("core.gamemode.spectator") ||
                player.hasPermission("core.gamemode")) {

            if (player.hasPermission("core.gamemode.creative.inventory") && currentGamemode != GameMode.CREATIVE) {
                if (debug) {
                    plugin.getLogger().info("[InventoryDebug] Skipping inventory management for " + player.getName() +
                            " in " + currentGamemode + " due to core.gamemode.creative.inventory permission");
                }
                return;
            }

            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Saving inventory for " + player.getName() +
                        " in " + currentGamemode + " before world change to " + toWorld.getName());
            }
            saveCurrentInventory(player, currentGamemode);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || !player.getWorld().equals(toWorld)) return;
                if (player.getGameMode() != currentGamemode) {
                    if (debug) {
                        plugin.getLogger().info("[InventoryDebug] Reverting gamemode for " + player.getName() +
                                " from " + player.getGameMode() + " to " + currentGamemode + " in world " + toWorld.getName());
                    }
                    player.setGameMode(currentGamemode);
                }
                if (player.hasPermission("core.gamemode.creative.inventory") && currentGamemode != GameMode.CREATIVE) {
                    if (debug) {
                        plugin.getLogger().info("[InventoryDebug] Skipping inventory restoration for " + player.getName() +
                                " in " + currentGamemode + " due to core.gamemode.creative.inventory permission");
                    }
                    return;
                }
                if (debug) {
                    plugin.getLogger().info("[InventoryDebug] Restoring inventory for " + player.getName() +
                            " in " + currentGamemode + " after world change to " + toWorld.getName());
                }
                loadInventory(player, currentGamemode);
            }, 2L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGamemodeSwitchRevert(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGamemode = event.getNewGameMode();
        GameMode oldGamemode = player.getGameMode();
        UUID playerUUID = player.getUniqueId();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");

        long currentTime = System.currentTimeMillis();
        if (recentRestorations.containsKey(playerUUID)) {
            long lastRestoration = recentRestorations.get(playerUUID);
            if (currentTime - lastRestoration < RESTORATION_COOLDOWN) {
                if (debug) {
                    plugin.getLogger().info("[InventoryDebug] Skipping game mode reversion for " + player.getName() + " due to recent restoration");
                }
                return;
            }
        }

        Plugin sourcePlugin = detectSourcePlugin();
        boolean isMultiverse = sourcePlugin != null && sourcePlugin.getName().equals("Multiverse-Core");

        if (isMultiverse && (
                (oldGamemode == GameMode.CREATIVE && player.hasPermission("core.gamemode.creative")) ||
                (oldGamemode == GameMode.SPECTATOR && player.hasPermission("core.gamemode.spectator")) ||
                player.hasPermission("core.gamemode") ||
                player.hasPermission("mv.bypass.gamemode." + player.getWorld().getName()) ||
                player.hasPermission("mv.bypass.gamemode.*"))) {

            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Detected Multiverse attempting to change " + player.getName() +
                        "'s gamemode from " + oldGamemode + " to " + newGamemode + ". Cancelling and preserving inventory.");
            }

            event.setCancelled(true);

            if (player.hasPermission("core.gamemode.creative.inventory") && oldGamemode != GameMode.CREATIVE) {
                if (debug) {
                    plugin.getLogger().info("[InventoryDebug] Skipping inventory management for " + player.getName() +
                            " in " + oldGamemode + " due to core.gamemode.creative.inventory permission");
                }
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                if (player.getGameMode() != oldGamemode) {
                    player.setGameMode(oldGamemode);
                    if (debug) {
                        plugin.getLogger().info("[InventoryDebug] Reverted " + player.getName() +
                                "'s gamemode to " + oldGamemode + " after Multiverse change attempt.");
                    }
                }
                if (player.hasPermission("core.gamemode.creative.inventory") && oldGamemode != GameMode.CREATIVE) {
                    if (debug) {
                        plugin.getLogger().info("[InventoryDebug] Skipping inventory restoration for " + player.getName() +
                                " in " + oldGamemode + " due to core.gamemode.creative.inventory permission");
                    }
                    return;
                }
                if (debug) {
                    plugin.getLogger().info("[InventoryDebug] Restoring inventory for " + player.getName() +
                            " in " + oldGamemode);
                }
                loadInventory(player, oldGamemode);
                recentRestorations.put(playerUUID, System.currentTimeMillis());
            }, 2L);
        }
    }

    private Plugin detectSourcePlugin() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        List<String> pluginPackagePrefixes = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.equals(this.plugin)) continue;
            String mainClass = plugin.getPluginMeta().getMainClass();
            String packagePrefix = mainClass.substring(0, mainClass.lastIndexOf('.'));
            pluginPackagePrefixes.add(packagePrefix);
        }
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            for (String prefix : pluginPackagePrefixes) {
                if (className.startsWith(prefix)) {
                    for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                        String mainClass = plugin.getPluginMeta().getMainClass();
                        String packagePrefix = mainClass.substring(0, mainClass.lastIndexOf('.'));
                        if (className.startsWith(packagePrefix)) {
                            return plugin;
                        }
                    }
                }
            }
            if (className.contains("Multiverse") || className.contains("multiverse")) {
                Plugin mvPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
                if (mvPlugin != null) {
                    return mvPlugin;
                }
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            if (!canDrop(player)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(
                        player,
                        "creative-manager.restrict",
                        plugin.getLangManager(),
                        "{action}", "drop that"
                );
                createFizzleEffect(player.getLocation());
            }
        }
    }

    private boolean canUseContainers(Player player) {
        return player.hasPermission("core.gamemode.creative.container");
    }

    private boolean canPickupItems(Player player) {
        return player.hasPermission("core.gamemode.creative.pickup");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canUseContainers(player)) return;
        Inventory inventory = event.getInventory();
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title()).toLowerCase();
        Set<InventoryType> restrictedTypes = new HashSet<>(Arrays.asList(
                InventoryType.CHEST, InventoryType.BARREL, InventoryType.DISPENSER,
                InventoryType.DROPPER, InventoryType.HOPPER, InventoryType.FURNACE,
                InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.BREWING,
                InventoryType.ENCHANTING, InventoryType.ANVIL, InventoryType.GRINDSTONE,
                InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.SMITHING,
                InventoryType.STONECUTTER, InventoryType.BEACON, InventoryType.SHULKER_BOX,
                InventoryType.CRAFTING, InventoryType.WORKBENCH
        ));
        Set<String> restrictedTitles = new HashSet<>(Arrays.asList(
                "chest", "large chest", "barrel", "shulker box", "dispenser", "dropper",
                "hopper", "furnace", "blast furnace", "smoker", "brewing stand", "anvil",
                "grindstone", "enchanting table", "loom", "cartography table", "smithing table",
                "stonecutter", "beacon", "crafting table"
        ));
        if (restrictedTypes.contains(inventory.getType())) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict",
                    plugin.getLangManager(), "{action}", "use this container");
            org.bukkit.block.Block targetBlock = player.getTargetBlock(null, 5);
            if (targetBlock.getType() != Material.AIR) {
                createFizzleEffect(targetBlock.getLocation());
            } else {
                createFizzleEffect(player.getLocation());
            }
            return;
        }
        for (String restrictedTitle : restrictedTitles) {
            if (title.contains(restrictedTitle)) {
                event.setCancelled(true);
                sendErrorMessageWithCooldown(player, "creative-manager.restrict",
                        plugin.getLangManager(), "{action}", "use this container");
                org.bukkit.block.Block targetBlock = player.getTargetBlock(null, 5);
                if (targetBlock.getType() != Material.AIR) {
                    createFizzleEffect(targetBlock.getLocation());
                } else {
                    createFizzleEffect(player.getLocation());
                }
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");
        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Inventory click detected for " + player.getName() + 
                    " in " + player.getGameMode() + ", slot: " + event.getSlot());
        }
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canUseContainers(player)) return;
        Inventory inventory = event.getClickedInventory();
        if (inventory == null || inventory.equals(player.getInventory())) return;
        Set<InventoryType> restrictedTypes = new HashSet<>(Arrays.asList(
                InventoryType.CHEST, InventoryType.BARREL, InventoryType.DISPENSER,
                InventoryType.DROPPER, InventoryType.HOPPER, InventoryType.FURNACE,
                InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.BREWING,
                InventoryType.ENCHANTING, InventoryType.ANVIL, InventoryType.GRINDSTONE,
                InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.SMITHING,
                InventoryType.STONECUTTER, InventoryType.BEACON, InventoryType.SHULKER_BOX,
                InventoryType.CRAFTING, InventoryType.WORKBENCH
        ));
        if (restrictedTypes.contains(inventory.getType())) {
            event.setCancelled(true);
            sendErrorMessageWithCooldown(player, "creative-manager.restrict",
                    plugin.getLangManager(), "{action}", "use this container");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");
        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Item pickup detected for " + player.getName() + 
                    " in " + player.getGameMode() + ", item: " + event.getItem().getItemStack().getType());
        }
        if (player.getGameMode() != GameMode.CREATIVE) return;
        if (canPickupItems(player)) return;
        event.setCancelled(true);
        sendErrorMessageWithCooldown(player, "creative-manager.restrict",
                plugin.getLangManager(), "{action}", "pick that up");
        createFizzleEffect(event.getItem().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        Material blockType = event.getBlock().getType();
        if (!canPlace(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                    "{action}", "place that");
            return;
        }
        if (badBlocks.contains(blockType) && !canBypassBlacklist(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                    "{action}", "place that");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        Material blockType = event.getBlock().getType();
        if (!canBreak(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                    "{action}", "break that");
            return;
        }
        if (badBlocks.contains(blockType) && !canBypassBlacklist(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                    "{action}", "break that");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (!event.hasBlock()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) return;
        Material blockType = event.getClickedBlock().getType();
        if (!canInteract(player)) {
            if (isInteractableBlock(blockType)) {
                event.setCancelled(true);
                createFizzleEffect(event.getClickedBlock().getLocation());
                sendErrorMessageWithCooldown(player, "creative-manager.restrict", plugin.getLangManager(),
                        "{action}", "interact with that");
                return;
            }
        }
        if (badBlocks.contains(blockType) && !canBypassBlacklist(player)) {
            event.setCancelled(true);
            createFizzleEffect(event.getClickedBlock().getLocation());
            sendErrorMessageWithCooldown(player, "creative-manager.blacklist", plugin.getLangManager(),
                    "{action}", "interacting with " + blockType.name());
        }
    }

    private boolean isInteractableBlock(Material material) {
        String name = material.name();
        if (name.contains("CHEST") ||
                name.contains("BARREL") ||
                name.contains("SHULKER") ||
                name.equals("DISPENSER") ||
                name.equals("DROPPER") ||
                name.equals("HOPPER") ||
                name.equals("FURNACE") ||
                name.equals("BLAST_FURNACE") ||
                name.equals("SMOKER") ||
                name.equals("BREWING_STAND") ||
                name.equals("ENDER_CHEST") ||
                name.contains("ANVIL") ||
                name.equals("BEACON") ||
                name.equals("LOOM") ||
                name.equals("STONECUTTER") ||
                name.equals("GRINDSTONE") ||
                name.equals("CARTOGRAPHY_TABLE") ||
                name.equals("ENCHANTING_TABLE") ||
                name.equals("SMITHING_TABLE")) {
            return true;
        }
        if (name.contains("BUTTON") ||
                name.contains("LEVER") ||
                name.contains("PRESSURE_PLATE") ||
                name.contains("TRAPDOOR") ||
                name.contains("DOOR") ||
                name.equals("REPEATER") ||
                name.equals("COMPARATOR") ||
                name.equals("DAYLIGHT_DETECTOR") ||
                name.equals("OBSERVER") ||
                name.equals("TRIPWIRE") ||
                name.equals("TARGET") ||
                name.equals("TRAPPED_CHEST") ||
                name.equals("NOTE_BLOCK") ||
                name.equals("JUKEBOX") ||
                name.equals("COMMAND_BLOCK") ||
                name.equals("CHAIN_COMMAND_BLOCK") ||
                name.equals("REPEATING_COMMAND_BLOCK") ||
                name.equals("STRUCTURE_BLOCK")) {
            return true;
        }
        return name.equals("LECTERN") ||
                name.equals("BELL") ||
                name.equals("ITEM_FRAME") ||
                name.equals("ARMOR_STAND") ||
                name.equals("RESPAWN_ANCHOR") ||
                name.equals("LODESTONE") ||
                name.equals("BED") ||
                name.equals("CAKE");
    }

    public void saveAllInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE && 
                !player.hasPermission("core.gamemode.creative.inventory")) {
                saveCurrentInventory(player, GameMode.CREATIVE);
            }
        }
    }

    private void saveCurrentInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");
        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Saving inventory for " + player.getName() +
                    " in " + gameMode + ", contents: " + Arrays.toString(player.getInventory().getContents()));
        }
        try {
            PlayerInventories inventories = database.loadPlayerInventories(playerUUID);
            ItemStack[] survivalInventory = new ItemStack[0];
            ItemStack[] survivalArmor = new ItemStack[0];
            ItemStack survivalOffhand = null;
            ItemStack[] creativeInventory = new ItemStack[0];
            ItemStack[] creativeArmor = new ItemStack[0];
            ItemStack creativeOffhand = null;
            if (inventories != null) {
                survivalInventory = inventories.getSurvivalInventory();
                survivalArmor = inventories.getSurvivalArmor();
                survivalOffhand = inventories.getSurvivalOffhand();
                creativeInventory = inventories.getCreativeInventory();
                creativeArmor = inventories.getCreativeArmor();
                creativeOffhand = inventories.getCreativeOffhand();
            }
            PlayerInventory inv = player.getInventory();
            switch (gameMode) {
                case SURVIVAL:
                case ADVENTURE:
                    survivalInventory = inv.getStorageContents();
                    survivalArmor = inv.getArmorContents();
                    survivalOffhand = inv.getItemInOffHand();
                    break;
                case CREATIVE:
                    creativeInventory = inv.getStorageContents();
                    creativeArmor = inv.getArmorContents();
                    creativeOffhand = inv.getItemInOffHand();
                    break;
                case SPECTATOR:
                    break;
            }
            PlayerInventories updated = new PlayerInventories(
                survivalInventory,
                survivalArmor,
                survivalOffhand,
                creativeInventory,
                creativeArmor,
                creativeOffhand
            );
            database.savePlayerInventories(playerUUID, updated);
            if (debug) {
                plugin.getLogger().info("Saved " + gameMode.name() + " inventory for " + playerName);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save inventory for " + playerName, e);
        }
    }

    private void loadInventory(@NotNull Player player, @NotNull GameMode gameMode) {
        UUID playerUUID = player.getUniqueId();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");
        if (player.hasPermission("core.gamemode.creative.inventory")) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Skipping inventory load for " + player.getName() +
                        " in " + gameMode + " due to core.gamemode.creative.inventory permission");
            }
            return;
        }
        try {
            PlayerInventories inventories = database.loadPlayerInventories(playerUUID);
            if (inventories == null) {
                if (debug) {
                    plugin.getLogger().info("[InventoryDebug] No saved inventory found for " + player.getName() + " in " + gameMode);
                }
                return;
            }
            ItemStack[] inventory;
            ItemStack[] armor;
            ItemStack offhand;
            if (gameMode == GameMode.CREATIVE) {
                inventory = inventories.getCreativeInventory();
                armor = inventories.getCreativeArmor();
                offhand = inventories.getCreativeOffhand();
            } else if (gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE) {
                inventory = inventories.getSurvivalInventory();
                armor = inventories.getSurvivalArmor();
                offhand = inventories.getSurvivalOffhand();
            } else {
                return;
            }
            if (inventory != null && inventory.length > 0) {
                player.getInventory().setContents(inventory);
            }
            if (armor != null && armor.length > 0) {
                player.getInventory().setArmorContents(armor);
            }
            if (offhand != null) {
                player.getInventory().setItemInOffHand(offhand);
            }
            player.updateInventory();
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Loaded " + gameMode + " inventory for " + player.getName() +
                        ": " + Arrays.toString(player.getInventory().getContents()));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load inventory for player: " + player.getName(), e);
        }
    }

    private void clearInventory(@NotNull Player player) {
        boolean debug = plugin.getConfig().getBoolean("debug-mode");
        if (player.hasPermission("core.gamemode.creative.inventory")) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Skipping inventory clear for " + player.getName() +
                        " in " + player.getGameMode() + " due to core.gamemode.creative.inventory permission");
            }
            return;
        }
        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Clearing inventory for " + player.getName() +
                    " in " + player.getGameMode() + " (World: " + player.getWorld().getName() + ")");
            plugin.getLogger().info("[InventoryDebug] Current inventory contents: " + Arrays.toString(player.getInventory().getContents()));
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (int i = 1; i < Math.min(stackTrace.length, 5); i++) {
                plugin.getLogger().info("[InventoryDebug] Stack trace [" + i + "]: " + stackTrace[i].toString());
            }
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.updateInventory();
    }

    public GameMode getLastKnownGamemode(UUID playerUUID) {
        return lastKnownGamemodes.getOrDefault(playerUUID, GameMode.SURVIVAL);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGameMode = event.getNewGameMode();
        GameMode oldGameMode = player.getGameMode();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");

        cachePlayerPermissions(player);

        if (player.hasPermission("core.gamemode.creative.inventory")) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Skipping inventory management for " +
                        player.getName() + " (old: " + oldGameMode + ", new: " + newGameMode +
                        ") due to core.gamemode.creative.inventory permission");
            }
            return;
        }

        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Saving " + oldGameMode + " inventory and loading " +
                    newGameMode + " inventory for " + player.getName());
        }
        saveCurrentInventory(player, oldGameMode);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // Ensure gamemode has updated
            if (player.getGameMode() == newGameMode) {
                if (debug) {
                    plugin.getLogger().info("[InventoryDebug] Applying " + newGameMode + " inventory for " + player.getName());
                }
                clearInventory(player); // Clear before loading to avoid overlap
                loadInventory(player, newGameMode);
                if (debug) {
                    plugin.getLogger().info("[InventoryDebug] Post-load inventory for " + player.getName() +
                            ": " + Arrays.toString(player.getInventory().getContents()));
                }
            }
        }, 2L); // Reduced delay to 2 ticks
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");
        if (player.hasPermission("core.gamemode.creative.inventory") && player.getGameMode() != GameMode.CREATIVE) {
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Skipping inventory management on respawn for " + 
                        player.getName() + " in " + player.getGameMode() + 
                        " due to core.gamemode.creative.inventory permission");
            }
            return;
        }
        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Loading inventory on respawn for " + 
                    player.getName() + " in " + player.getGameMode());
        }
        loadInventory(player, player.getGameMode());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryChange(InventoryEvent event) {
        if (event.getInventory().getHolder() instanceof Player player) {
            boolean debug = plugin.getConfig().getBoolean("debug-mode");
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Inventory changed for " + player.getName() + 
                        " in " + player.getGameMode() + " at " + System.currentTimeMillis() + 
                        ", contents: " + Arrays.toString(player.getInventory().getContents()));
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                for (int i = 1; i < Math.min(stackTrace.length, 5); i++) {
                    plugin.getLogger().info("[InventoryDebug] Stack trace [" + i + "]: " + stackTrace[i].toString());
                }
            }
            // Force save inventory on change
            saveCurrentInventory(player, player.getGameMode());
        }
    }

    private final Set<UUID> bypassInventoryManagement = ConcurrentHashMap.newKeySet();

    public void setBypassInventoryManagement(Player player, boolean bypass) {
        if (bypass) {
            bypassInventoryManagement.add(player.getUniqueId());
        } else {
            bypassInventoryManagement.remove(player.getUniqueId());
        }
    }

    public void cleanup() {
        if (gamemodeCheckTask != null) {
            gamemodeCheckTask.cancel();
            gamemodeCheckTask = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void saveOnInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            boolean debug = plugin.getConfig().getBoolean("debug-mode");
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Inventory click detected for " + player.getName() + 
                        " in " + player.getGameMode() + ", slot: " + event.getSlot());
            }
            saveCurrentInventory(player, player.getGameMode());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void saveOnItemPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            boolean debug = plugin.getConfig().getBoolean("debug-mode");
            if (debug) {
                plugin.getLogger().info("[InventoryDebug] Item pickup detected for " + player.getName() + 
                        " in " + player.getGameMode() + ", item: " + event.getItem().getItemStack().getType());
            }
            saveCurrentInventory(player, player.getGameMode());
        }
    }

    private void checkAndApplyGameModeReset(Player player) {
        UUID playerId = player.getUniqueId();
        GameMode lastKnownGamemode = getLastKnownGamemode(playerId);
        GameMode currentGamemode = player.getGameMode();
        boolean debug = plugin.getConfig().getBoolean("debug-mode");

        if (debug) {
            plugin.getLogger().info("[InventoryDebug] Checking game mode reset for " + player.getName() +
                    ": lastKnown=" + lastKnownGamemode + ", current=" + currentGamemode);
        }

        if (lastKnownGamemode != currentGamemode) {
            if ((lastKnownGamemode == GameMode.CREATIVE && player.hasPermission("core.gamemode.creative")) ||
                    (lastKnownGamemode == GameMode.SPECTATOR && player.hasPermission("core.gamemode.spectator")) ||
                    player.hasPermission("core.gamemode")) {
                if (debug) {
                    plugin.getLogger().info("[InventoryDebug] Detected gamemode change for " + player.getName() +
                            " from " + currentGamemode + " to " + lastKnownGamemode +
                            ". Will revert this change.");
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline()) return;
                    player.setGameMode(lastKnownGamemode);
                    if (debug) {
                        plugin.getLogger().info("[InventoryDebug] Reverted " + player.getName() +
                                "'s gamemode back to " + lastKnownGamemode +
                                " after it was changed to " + currentGamemode);
                    }
                    if (!player.hasPermission("core.gamemode.creative.inventory")) {
                        loadInventory(player, lastKnownGamemode);
                    }
                }, 2L);
            }
        }
    }
}