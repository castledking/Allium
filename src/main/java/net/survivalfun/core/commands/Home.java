package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;

import org.bukkit.Material;
import org.bukkit.World;
import net.survivalfun.core.listeners.jobs.CancelTeleportation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class Home implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final Database database;
    private final Lang lang;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private int homeCooldown;
    private int teleportDelay;
    private boolean showLocation;
    private static final double TELEPORT_LENIENCY = 0.5; // Allowed movement distance in blocks
    

    public Home(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
        this.lang = plugin.getLangManager();
        loadConfig();
    }

    private void loadConfig() {
        homeCooldown = plugin.getConfig().getInt("home.cooldown", 30);
        teleportDelay = plugin.getConfig().getInt("teleport.delay", 3);
        showLocation = plugin.getConfig().getBoolean("home.show-location", false);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player)) {
            Text.sendErrorMessage(sender, "not-a-player", lang);
            return true;
        }

        Player player = (Player) sender;
        String commandName = command.getName().toLowerCase();
        

        if (commandName.equals("sethome")) {
            return handleSetHome(player, args);
        } else if (commandName.equals("home")) {
            return handleHome(player, args);
        } else if (commandName.equals("homes")) {
            return handleHomes(player);
        } else if (commandName.equals("delhome") || commandName.equals("removehome")) {
            return handleDelHome(player, args);
        }

        return false;
    }

    private boolean handleSetHome(Player player, String[] args) {
        if (!player.hasPermission("allium.sethome")) {
            Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "set homes.", true);
            return true;
        }

        String homeName = args.length > 0 ? args[0] : "home";
        
        // Check if home name is valid
        if (!isValidHomeName(homeName)) {
            Text.sendErrorMessage(player, "home.invalid-name", lang);
            return true;
        }
        
        // Check for case-insensitive duplicate
        String existingHome = findCaseInsensitiveHome(player.getUniqueId(), homeName);
        if (existingHome != null) {
            Text.sendErrorMessage(player, "home.already-set", lang, "{home}", existingHome);
            return true;
        }

        int maxHomes = getMaxHomes(player);
        int currentHomes = database.getPlayerHomeCount(player.getUniqueId());

        if (currentHomes >= maxHomes && !database.getPlayerHomes(player.getUniqueId()).contains(homeName)) {
            Text.sendErrorMessage(player, "home.max", lang, "{max}", String.valueOf(maxHomes));
            return true;
        }

        Location location = player.getLocation();
        long timestamp = System.currentTimeMillis();
        database.savePlayerHome(player.getUniqueId(), homeName, location, timestamp);

        String locationString = showLocation ? formatLocation(location) : lang.getFirstColorCode("home.set") + "current location";

        String finalMsg = plugin.getLangManager().get("home.set"
        .replace("{home}", homeName)
        .replace("{location}", showLocation ? locationString : ""));

        lang.sendMessage(player, "home.set", finalMsg);
        return true;    
    }

    private boolean handleHome(Player player, String[] args) {
        if (!player.hasPermission("allium.home")) {
            Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "teleport to homes.", true);
            return true;
        }

        // Check cooldown
        if (!player.hasPermission("allium.home.nocooldown") && hasCooldown(player.getUniqueId())) {
            long remaining = getRemainingCooldown(player.getUniqueId());
            Text.sendErrorMessage(player, "cooldown", lang, "{time}", Text.formatTime((int) remaining), "{cmd}", "home");
            return true;
        }

        if (args.length == 0) {
            List<String> homes = database.getPlayerHomes(player.getUniqueId());
            if (homes.size() == 1) {
                return teleportToHome(player, player.getUniqueId(), homes.get(0));
            } else if (homes.size() > 1) {
                return handleHomes(player);
            } else {
                boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
                if (spawnIfNoHome) {
                    Spawn spawn = new Spawn(plugin);
                    spawn.teleportToSpawn(player);
                    return true;
                } else {
                    Text.sendErrorMessage(player, "home.no-home", lang);
                    return true;
                }
            }
        }

        String arg = args[0];
        if (arg.contains(":")) {
            String[] parts = arg.split(":", 2);
            String targetName = parts[0];
            String homeName = parts.length > 1 ? parts[1] : "";
            
            // Check if it's a player:home format for admin teleport
            if (!player.hasPermission("allium.home.others") && !player.getName().equals(targetName)) {
                // self home list
                List<String> homes = database.getPlayerHomes(player.getUniqueId());
                if (homes.size() == 1) {
                    return teleportToHome(player, player.getUniqueId(), homes.get(0));
                } else if (homes.size() > 1) {
                    String homeList = getFormattedHomeList(player, homes, player.getUniqueId());
                    String finalMsg = lang.get("home.list"
                        .replace("{homes}", homeList));
                    lang.sendMessage(player, "home.list", finalMsg);
                    return handleHomes(player);
                } else {
                    boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
                    if (spawnIfNoHome) {
                        Spawn spawn = new Spawn(plugin);
                        spawn.teleportToSpawn(player);
                        return true;
                    } else {
                        Text.sendErrorMessage(player, "home.no-home", lang);
                        return true;
                    }
                }
            }

            
            // If no home name provided after :, show their homes
            if (homeName.isEmpty()) {
                UUID targetUUID = getPlayerUUID(targetName);
                if (targetUUID == null) {
                    if (Bukkit.getOfflinePlayer(targetName).hasPlayedBefore()) {
                        Text.sendErrorMessage(player, "player-not-online", lang, "{player}", targetName);
                        return true;
                    } else {
                        Text.sendErrorMessage(player, "player-not-found", lang, "{player}", targetName);
                        return true;
                    }
                }
                return handleOtherPlayerHomes(player, targetUUID, targetName);
            }
            
            // If home name is 'bed', handle bed teleportation for the target player
            if (homeName.equalsIgnoreCase("bed")) {
                if (!player.hasPermission("allium.home.others.bed")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "teleport to other players' beds.", true);
                    return true;
                }
                
                UUID targetUUID = getPlayerUUID(targetName);
                if (targetUUID == null) {
                    if (Bukkit.getOfflinePlayer(targetName).hasPlayedBefore()) {
                        Text.sendErrorMessage(player, "player-not-online", lang, "{player}", targetName);
                        return true;
                    } else {
                        Text.sendErrorMessage(player, "player-not-found", lang, "{player}", targetName);
                        return true;
                    }
                }
                
                // If target is online, get their bed location directly
                Player targetPlayer = Bukkit.getPlayer(targetUUID);
                Location bedLocation = targetPlayer != null ? targetPlayer.getBedSpawnLocation() : 
                    Bukkit.getOfflinePlayer(targetUUID).getBedSpawnLocation();
                
                if (bedLocation == null || !isBedAccessible(bedLocation)) {
                    Text.sendErrorMessage(player, "home.no-bed-other", lang, "{name}", targetName);
                    return true;
                }
                
                // Teleport to the target's bed
                if (player.hasPermission("allium.tpa.nodelay") || teleportDelay <= 0) {
                    player.teleportAsync(bedLocation).thenAccept(success -> {
                        if (success) {
                            if (!player.hasPermission("allium.home.nocooldown")) {
                                setCooldown(player.getUniqueId());
                            }
                            if (player.isOnline()) {
                                String finalMsg = lang.get("home.bed-other"
                                .replace("{name}", targetName)
                                .replace("{home}", homeName)
                                .replace("{location}", showLocation ? formatLocation(bedLocation) : ""));
                                lang.sendMessage(player, "home.bed-other", finalMsg);
                            }
                        } else if (player.isOnline()) {
                            player.sendMessage(Text.colorize("&cTeleport failed. Please try again."));
                        }
                    });
                } else {
                    lang.sendMessage(player, "tp.delay", "{delay}", Text.formatTime(Math.max(teleportDelay, 0)));
                    CancelTeleportation listener = new CancelTeleportation(plugin, player, player.getLocation(), TELEPORT_LENIENCY);
                    Bukkit.getPluginManager().registerEvents(listener, plugin);
                    player.getScheduler().runDelayed(plugin, scheduledTask -> {
                        if (!listener.isCancelled() && player.isOnline()) {
                            player.teleportAsync(bedLocation).thenAccept(success -> {
                                if (success) {
                                    if (!player.hasPermission("allium.home.nocooldown")) {
                                        setCooldown(player.getUniqueId());
                                    }
                                    if (player.isOnline()) {
                                        lang.sendMessage(player, "tp.success"
                                            .replace("{name}", "to " + targetName + "'s bed")
                                            .replace("{target}", showLocation ? "at " + formatLocation(bedLocation) : ""));
                                    }
                                } else if (player.isOnline()) {
                                    lang.sendMessage(player, "&cTeleport failed. Please try again.");
                                }
                            });
                        }
                        HandlerList.unregisterAll(listener);
                    }, null, teleportDelay * 20L);
                }
                return true;
            }
            
            // Regular home teleport for other players
            UUID targetUUID = getPlayerUUID(targetName);
            if (targetUUID == null) {
                if (Bukkit.getOfflinePlayer(targetName).hasPlayedBefore()) {
                    Text.sendErrorMessage(player, "player-not-online", lang, "{player}", targetName);
                    return true;
                } else {
                    Text.sendErrorMessage(player, "player-not-found", lang, "{player}", targetName);
                    return true;
                }
            }
            return teleportToHome(player, targetUUID, homeName);
        } else {
            // Handle special cases like "bed"
            if (arg.equalsIgnoreCase("bed")) {
                if (!player.hasPermission("allium.home.bed")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "teleport to your bed.", true);
                    return true;
                }
                Location bedLocation = player.getBedSpawnLocation();
                if (bedLocation == null || !isBedAccessible(bedLocation)) {
                    Text.sendErrorMessage(player, "home.no-bed", lang);
                    return true;
                }
                if (player.hasPermission("allium.tpa.nodelay") || teleportDelay <= 0) {
                    player.teleportAsync(bedLocation).thenAccept(success -> {
                        if (success) {
                            if (!player.hasPermission("allium.home.nocooldown")) {
                                setCooldown(player.getUniqueId());
                            }
                            if (player.isOnline()) {
                                lang.sendMessage(player, "tp.success"
                                    .replace("{name}", "to your bed")
                                    .replace("{target}", showLocation ? "at " + formatLocation(bedLocation) : ""));
                            }
                        } else {
                            if (player.isOnline()) {
                                lang.sendMessage(player, "&cTeleport failed. Please try again.");
                            }
                        }
                    });
                } else {
                    lang.sendMessage(player, "tp.delay", "{delay}", Text.formatTime(Math.max(teleportDelay, 0)));
                    CancelTeleportation listener = new CancelTeleportation(plugin, player, player.getLocation(), TELEPORT_LENIENCY);
                    Bukkit.getPluginManager().registerEvents(listener, plugin);
                    player.getScheduler().runDelayed(plugin, scheduledTask -> {
                        if (!listener.isCancelled() && player.isOnline()) {
                            player.teleportAsync(bedLocation).thenAccept(success -> {
                                if (success) {
                                    if (!player.hasPermission("allium.home.nocooldown")) {
                                        setCooldown(player.getUniqueId());
                                    }
                                    if (player.isOnline()) {
                                        lang.sendMessage(player, "tp.success"
                                            .replace("{name}", "to your bed")
                                            .replace("{target}", showLocation ? "at " + formatLocation(bedLocation) : ""));
                                    }
                                } else {
                                    if (player.isOnline()) {
                                        lang.sendMessage(player, "&cTeleport failed. Please try again.");
                                    }
                                }
                            });
                        }
                        HandlerList.unregisterAll(listener);
                    }, null, teleportDelay * 20L); // Convert seconds to ticks
                }
                return true;
            }

            // Regular home teleport - check if the home exists and teleport
            String existingHome = findCaseInsensitiveHome(player.getUniqueId(), arg);
            if (existingHome != null) {
                return teleportToHome(player, player.getUniqueId(), existingHome);
            } else {
                // Home not found, show error or list of available homes
                List<String> homes = database.getPlayerHomes(player.getUniqueId());
                if (homes.size() == 1) {
                    return teleportToHome(player, player.getUniqueId(), homes.get(0));
                } else if (homes.size() > 1) {
                    Text.sendErrorMessage(player, "home.not-found", lang, "{home}", arg);
                    return handleHomes(player);
                } else {
                    boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
                    if (spawnIfNoHome) {
                        Spawn spawn = new Spawn(plugin);
                        if (plugin.getDatabase().getSpawnLocation() == null) {
                            lang.sendMessage(player, "home.no-home");
                            return true;
                        }
                        spawn.teleportToSpawn(player);
                        return true;
                    } else {
                        Text.sendErrorMessage(player, "home.no-home", lang);
                        return true;
                    }
                }
            }
        }
    }

    private boolean handleHomes(Player player) {
        if (!player.hasPermission("allium.home")) {
            Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "list homes.", true);
            return true;
        }

        List<String> homes = database.getPlayerHomes(player.getUniqueId());
        String homeList = getFormattedHomeList(player, homes, player.getUniqueId());
        if (homes.isEmpty() && !player.hasPermission("allium.home.bed")) {
            boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", true);
            if (spawnIfNoHome) {
                Spawn spawn = new Spawn(plugin);
                spawn.teleportToSpawn(player);
            } else {
                Text.sendErrorMessage(player, "home.no-home", lang);
            }
        } else {
            lang.sendMessage(player, "home.list", "{homes}", homeList);
        }
        return true;
    }

    private boolean handleOtherPlayerHomes(Player player, UUID targetUUID, String targetName) {
        List<String> homes = database.getPlayerHomes(targetUUID);
        String homeList = getFormattedHomeList(player, homes, targetUUID);
        if (homes.isEmpty() && !player.hasPermission("allium.home.bed")) {
            boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", true);
            if (spawnIfNoHome) {
                Spawn spawn = new Spawn(plugin);
                spawn.teleportToSpawn(player);
                return true;
            } else {
                Text.sendErrorMessage(player, "home.no-home-other", lang, "{name}", targetName);
                return true;
            }
        } else {
            lang.sendMessage(player, "home.list", "{homes}", homeList);
        }
        return true;
    }

    private boolean handleDelHome(Player player, String[] args) {
        if (!player.hasPermission("allium.delhome")) {
            Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "delete homes.");
            return true;
        }

        if (args.length == 0) {
            List<String> homes = database.getPlayerHomes(player.getUniqueId());
            if (homes.size() == 1) {
                return deleteHome(player, player.getUniqueId(), homes.get(0));
                } else if (homes.size() > 1) {
                    String homeList = getFormattedHomeList(player, homes, player.getUniqueId());
                    lang.sendMessage(player, "home.list", "{homes}", homeList);
                    return handleHomes(player);
                } else {
                    boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
                    if (spawnIfNoHome) {
                        Spawn spawn = new Spawn(plugin);
                    spawn.teleportToSpawn(player);
                    return true;
                } else {
                    Text.sendErrorMessage(player, "home.no-home", lang);
                    return true;
                }
            }
        }

        String arg = args[0];
        if (arg.contains(":")) {
            if (!player.hasPermission("allium.delhome.others")) {
                // list self homes
                List<String> homes = database.getPlayerHomes(player.getUniqueId());
                if (homes.size() == 1) {
                    return deleteHome(player, player.getUniqueId(), homes.get(0));
                } else if (homes.size() > 1) {
                    String homeList = getFormattedHomeList(player, homes, player.getUniqueId());
                    lang.sendMessage(player, "home.list", "{homes}", homeList);
                    return handleHomes(player);
                } else {
                    boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
                    if (spawnIfNoHome) {
                        Spawn spawn = new Spawn(plugin);
                        spawn.teleportToSpawn(player);
                        return true;
                    } else {
                        Text.sendErrorMessage(player, "home.no-home", lang);
                        return true;
                    }
                }
            }

            String[] parts = arg.split(":", 2);
            if (parts.length != 2) {
                Text.sendErrorMessage(player, "invalid", lang, "{arg}", "format");
                return true;
            }

            String targetName = parts[0];
            String homeName = parts[1];
            if (!isValidHomeName(homeName)) {
                Text.sendErrorMessage(player, "invalid", lang, "{arg}", "home name", "Please check your syntax.", "Please use at least 2 characters (letters, numbers, underscores, or hyphens).");
                return true;
            }

            UUID targetUUID = getPlayerUUID(targetName);
            if (targetUUID == null) {
                Text.sendErrorMessage(player, "player-not-found", lang, "{player}", targetName);
                return true;
            }

            return deleteHome(player, targetUUID, homeName);
        } else {
            if (!isValidHomeName(arg)) {
                Text.sendErrorMessage(player, "invalid", lang, "{arg}", "home name", "Please check your syntax.", "Please use at least 2 characters (letters, numbers, underscores, or hyphens).");
                return true;
            }
            return deleteHome(player, player.getUniqueId(), arg);
        }
    }

    private boolean teleportToHome(Player player, UUID targetUUID, String homeName) {
        // First try exact match
        Location home = database.getPlayerHome(targetUUID, homeName);
        String actualHomeName = homeName;
        
        // If exact match not found, try case-insensitive match
        if (home == null) {
            String foundHome = findCaseInsensitiveHome(targetUUID, homeName);
            if (foundHome != null) {
                home = database.getPlayerHome(targetUUID, foundHome);
                actualHomeName = foundHome; // Use the actual home name with correct case
            } else {
                Text.sendErrorMessage(player, "home.not-found", lang, "{home}", homeName);
                return true;
            }
        }

        Location finalHome = home;
        String finalHomeName = actualHomeName;
        
        if (player.hasPermission("allium.tpa.nodelay") || teleportDelay <= 0) {
            player.teleportAsync(finalHome).thenAccept(success -> {
                if (success) {
                    if (!player.hasPermission("allium.home.nocooldown")) {
                        setCooldown(player.getUniqueId());
                    }
                    if (player.isOnline()) {
                        String targetPlayerName = targetUUID.equals(player.getUniqueId()) ? "" : " " + Bukkit.getOfflinePlayer(targetUUID).getName() + "'s";
                        lang.sendMessage(player, "tp.success",
                            "{name}", "to" + targetPlayerName + " " + finalHomeName,
                            "{target}", showLocation ? "at " + formatLocation(finalHome) : "");
                    }
                } else {
                    if (player.isOnline()) {
                        lang.sendMessage(player, "&cTeleport failed. Please try again.");
                    }
                }
            });
        } else {
            lang.sendMessage(player, "tp.delay", "{delay}", Text.formatTime(Math.max(teleportDelay, 0)));
            CancelTeleportation listener = new CancelTeleportation(plugin, player, player.getLocation(), TELEPORT_LENIENCY);
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            player.getScheduler().runDelayed(plugin, scheduledTask -> {
                if (!listener.isCancelled() && player.isOnline()) {
                    player.teleportAsync(finalHome).thenAccept(success -> {
                        if (success) {
                            if (!player.hasPermission("allium.home.nocooldown")) {
                                setCooldown(player.getUniqueId());
                            }
                            if (player.isOnline()) {
                                String targetPlayerName = targetUUID.equals(player.getUniqueId()) ? "" : " " + Bukkit.getOfflinePlayer(targetUUID).getName() + "'s";
                                lang.sendMessage(player, "tp.success",
                                    "{name}", "to" + targetPlayerName + " " + finalHomeName,
                                    "{target}", showLocation ? "at " + formatLocation(finalHome) : "");
                            }
                        } else {
                            if (player.isOnline()) {
                                lang.sendMessage(player, "&cTeleport failed. Please try again.");
                            }
                        }
                    });
                }
                HandlerList.unregisterAll(listener);
            }, null, teleportDelay * 20L); // Convert seconds to ticks
        }
        return true;
    }

    private boolean deleteHome(Player player, UUID targetUUID, String homeName) {
        // First try exact match
        Location home = database.getPlayerHome(targetUUID, homeName);
        String actualHomeName = homeName;
        
        // If exact match not found, try case-insensitive match
        if (home == null) {
            String foundHome = findCaseInsensitiveHome(targetUUID, homeName);
            if (foundHome != null) {
                actualHomeName = foundHome; // Use the actual home name with correct case
            } else {
                Text.sendErrorMessage(player, "home.not-found", lang, "{home}", homeName);
                return true;
            }
        }

        String finalHomeName = actualHomeName;
        boolean deleted = database.deletePlayerHome(targetUUID, finalHomeName);
        if (deleted) {
            lang.sendMessage(player, "home.remove".replace("{home}", finalHomeName));
        } else {
            String homeList = getFormattedHomeList(player, database.getPlayerHomes(targetUUID), targetUUID);
            String target = targetUUID.equals(player.getUniqueId()) ? "your" : targetUUID.toString();
            lang.sendMessage(player, "home.not-found"
                .replace("{home}", finalHomeName)
                .replace("{target}", target)
                .replace("{homes}", homeList));
        }
        return true;
    }

    private String getFormattedHomeList(Player currentPlayer, List<String> homes, UUID targetPlayerUUID) {
        List<String> formattedHomes = new ArrayList<>();
        
        // Add regular homes with yellow color
        for (String home : homes) {
            formattedHomes.add("§e" + home);
        }
        
        // Get the target player's bed location
        Player targetPlayer = plugin.getServer().getPlayer(targetPlayerUUID);
        Location bedLocation = null;
        
        if (targetPlayer != null && targetPlayer.isOnline()) {
            // Player is online, get their current bed location
            bedLocation = targetPlayer.getBedSpawnLocation();
        } else {
            // Player is offline, we can't get their bed location easily
            // For now, we'll skip showing bed for offline players
            // This could be enhanced later by storing bed locations in database
        }
        
        // Only show bed if target player has set a bed spawn and has bed permission
        if (bedLocation != null && currentPlayer.hasPermission("allium.home.bed")) {
            boolean bedAccessible = isBedAccessible(bedLocation);
            // Accessible beds are italic and yellow, inaccessible are strikethrough (not italic)
            String bedFormat = bedAccessible ? "§e§obed" : "§e§mbed§r";
            formattedHomes.add(bedFormat);
        }

        
        return formattedHomes.isEmpty() ? "§cnone" : String.join("§7, ", formattedHomes);
    }

    private boolean isBedAccessible(Location bedLocation) {
        if (bedLocation == null) {
            return false;
        }

        World world = bedLocation.getWorld();
        if (world == null) {
            return false;
        }

        // Check if the bed spawn location is within valid world bounds
        if (bedLocation.getY() < world.getMinHeight() || bedLocation.getY() >= world.getMaxHeight()) {
            return false;
        }

        // If chunk isn't loaded, assume it's accessible (let teleport handle chunk loading)
        int chunkX = bedLocation.getBlockX() >> 4;
        int chunkZ = bedLocation.getBlockZ() >> 4;

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return true;
        }

        // For loaded chunks, check if the block at bed location is safe for spawning
        // Minecraft allows bed spawns even if the bed is broken, but we should check
        // if the location is safe (not in lava, not solid blocks, etc.)
        Material blockType = world.getBlockAt(bedLocation).getType();

        // Check if the block is dangerous (lava, fire, etc.)
        if (blockType == Material.LAVA || blockType == Material.FIRE || blockType == Material.SOUL_FIRE) {
            return false;
        }

        // Check if the block above is safe (not solid, allows spawning)
        Location aboveLocation = bedLocation.clone().add(0, 1, 0);
        Material aboveBlock = world.getBlockAt(aboveLocation).getType();

        // Can't spawn if there's a solid block above
        if (aboveBlock.isSolid()) {
            return false;
        }

        // Check if the block two above is also safe (for double-height mobs)
        Location twoAboveLocation = bedLocation.clone().add(0, 2, 0);
        Material twoAboveBlock = world.getBlockAt(twoAboveLocation).getType();

        if (twoAboveBlock.isSolid()) {
            return false;
        }

        return true;
    }

    private boolean isValidHomeName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        
        // Check if name is a single digit (not allowed)
        if (name.length() == 1 && Character.isDigit(name.charAt(0))) {
            return false;
        }
        
        // Check for valid characters (letters, numbers, underscores, hyphens)
        return name.matches("^[a-zA-Z0-9_\\-]+$");
    }
    
    private String findCaseInsensitiveHome(UUID playerUUID, String homeName) {
        List<String> homes = database.getPlayerHomes(playerUUID);
        if (homes == null || homes.isEmpty()) {
            return null;
        }
        
        String lowerHomeName = homeName.toLowerCase();
        for (String home : homes) {
            if (home.toLowerCase().equals(lowerHomeName)) {
                return home; // Return the original case version
            }
        }
        return null;
    }

    private int getMaxHomes(Player player) {
        int dbMax = database.getPlayerMaxHomes(player.getUniqueId());
        if (dbMax >= 0) {
            return dbMax;
        }
        for (int i = 100; i >= 0; i--) {
            if (player.hasPermission("allium.sethome." + i)) {
                return i;
            }
        }
        return player.hasPermission("allium.sethome") ? 1 : 0;
    }

    private UUID getPlayerUUID(String playerName) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        return offlinePlayer.hasPlayedBefore() || offlinePlayer.isOnline() ? offlinePlayer.getUniqueId() : null;
    }

    private String formatLocation(Location location) {
        return String.format("x:%.2f, y:%.2f, z:%.2f in %s",
                location.getX(), location.getY(), location.getZ(), location.getWorld().getName());
    }

    private boolean hasCooldown(UUID playerId) {
        if (cooldowns.containsKey(playerId)) {
            long cooldownTime = cooldowns.get(playerId);
            return System.currentTimeMillis() < cooldownTime;
        }
        return false;
    }

    private long getRemainingCooldown(UUID playerId) {
        if (!hasCooldown(playerId)) return 0;
        return (cooldowns.get(playerId) - System.currentTimeMillis()) / 1000 + 1; // +1 to round up
    }

    private void setCooldown(UUID playerId) {
        cooldowns.put(playerId, System.currentTimeMillis() + (homeCooldown * 1000L));
    }

    

    public void onDisable() {
        cooldowns.clear();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        Player player = (Player) sender;
        String cmd = command.getName().toLowerCase();
        
        if (!cmd.equals("home") && !cmd.equals("delhome") && !cmd.equals("removehome")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String arg = args[0];
            List<String> completions = new ArrayList<>();
            
            // Suggest self homes
            List<String> selfHomes = database.getPlayerHomes(player.getUniqueId());
            completions.addAll(selfHomes.stream()
                .filter(home -> home.toLowerCase().startsWith(arg.toLowerCase()))
                .collect(Collectors.toList()));
            
            // Suggest bed if player has permission
            if (player.hasPermission("allium.home.bed") && "bed".startsWith(arg.toLowerCase())) {
                completions.add("bed");
            }
            
            // Suggest other players with colon syntax if permission
            if (player.hasPermission("allium.home.others")) {
                if (arg.contains(":")) {
                    String[] parts = arg.split(":", 2);
                    String playerName = parts[0];
                    String homePrefix = parts.length > 1 ? parts[1] : "";
                    
                    OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
                    if (target != null && target.hasPlayedBefore()) {
                        List<String> targetHomes = database.getPlayerHomes(target.getUniqueId());
                        return targetHomes.stream()
                            .filter(home -> home.toLowerCase().startsWith(homePrefix.toLowerCase()))
                            .map(home -> playerName + ":" + home)
                            .collect(Collectors.toList());
                    }
                } else {
                    List<String> playerSuggestions = Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(arg.toLowerCase()))
                        .map(name -> name + ":")
                        .collect(Collectors.toList());
                    completions.addAll(playerSuggestions);
                }
            }
            
            return completions;
        }
        return Collections.emptyList();
    }
}
