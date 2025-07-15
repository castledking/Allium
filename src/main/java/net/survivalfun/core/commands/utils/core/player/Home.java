package net.survivalfun.core.commands.utils.core.player;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.commands.teleportation.Spawn;
import net.survivalfun.core.commands.teleportation.TP;
import net.survivalfun.core.listeners.jobs.TeleportCancelListener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
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
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Home implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final Database database;
    private final Lang lang;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private int homeCooldown;
    private int teleportDelay;
    private boolean showLocation;
    private static final Pattern HOME_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{2,}$");
    private static final double TELEPORT_LENIENCY = 0.5; // Allowed movement distance in blocks
    

    public Home(PluginStart plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        this.lang = plugin.getLangManager();
        loadConfig();
    }

    private void loadConfig() {
        homeCooldown = plugin.getConfig().getInt("home.cooldown", 30);
        teleportDelay = plugin.getConfig().getInt("teleport.delay", 3);
        showLocation = plugin.getConfig().getBoolean("home.show-location", true);
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
        if (!isValidHomeName(homeName)) {
            Text.sendErrorMessage(player, "invalid", lang, "{arg}", "home name", "Please check your syntax.", "Please use at least 2 characters (letters, numbers, underscores, or hyphens).");
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
        player.sendMessage(lang.get("home.set")
            .replace("{home}", homeName)
            .replace("{location}", showLocation ? formatLocation(location) : "current location"));
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
                    Spawn spawn = new Spawn(plugin, database);
                    spawn.teleportToSpawn(player);
                    return true;
                } else {
                    Text.sendErrorMessage(player, "home.no-home", lang);
                    return true;
                }
            }
        }

        String arg = args[0];
        if (arg.equalsIgnoreCase("bed")) {
            if (!player.hasPermission("allium.home.bed")) {
                Text.sendErrorMessage(player, "no-permission", lang, "use /home bed", "teleport to your bed.", true);
                return true;
            }
            Location bedLocation = player.getBedSpawnLocation();
            if (bedLocation == null || !isBedAccessible(bedLocation)) {
                Text.sendErrorMessage(player, "home.no-bed", lang);
                return true;
            }
            if (player.hasPermission("core.tpa.nodelay") || teleportDelay <= 0) {
                savePlayerLocationToBackHistory(player);
                player.teleport(bedLocation);
                if (!player.hasPermission("core.home.nocooldown")) {
                    setCooldown(player.getUniqueId());
                }
                player.sendMessage(lang.get("tp.success")
                    .replace("{name}", "to bed")
                    .replace("{target}", "at " + formatLocation(bedLocation)));
            } else {
                player.sendMessage(lang.get("tp.delay")
                    .replace("{delay}", String.valueOf(teleportDelay)));
                TeleportCancelListener listener = new TeleportCancelListener(plugin, player, player.getLocation(), TELEPORT_LENIENCY);
                Bukkit.getPluginManager().registerEvents(listener, plugin);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!listener.isCancelled()) {
                        savePlayerLocationToBackHistory(player);
                        player.teleport(bedLocation);
                        if (!player.hasPermission("core.home.nocooldown")) {
                            setCooldown(player.getUniqueId());
                        }
                        
                        player.sendMessage(lang.get("tp.success")
                            .replace("{name}", "to bed")
                            .replace("{target}", showLocation ? "at " + formatLocation(bedLocation) : ""));
                    }
                    HandlerList.unregisterAll(listener);
                }, teleportDelay * 20L); // Convert seconds to ticks
            }
            return true;
        }

        if (arg.contains(":")) {
            if (!player.hasPermission("allium.home.others")) {
                Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "teleport to other player's homes.", true);
                return true;
            }

            String[] parts = arg.split(":", 2);
            String targetName = parts[0];
            String homeName = parts.length > 1 ? parts[1] : "";

            UUID targetUUID = getPlayerUUID(targetName);
            if (targetUUID == null) {
                Text.sendErrorMessage(player, "player-not-found", lang, "{player}", targetName);
                return true;
            }

            List<String> targetHomes = database.getPlayerHomes(targetUUID);
            if (homeName.isEmpty()) {
                return handleOtherPlayerHomes(player, targetUUID, targetName);
            } else {
                if (targetHomes.size() == 1) {
                    return teleportToHome(player, targetUUID, targetHomes.get(0));
                } else if (targetHomes.size() > 1) {
                    return handleOtherPlayerHomes(player, targetUUID, targetName);
                } else {
                    boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
                    if (spawnIfNoHome) {
                        Spawn spawn = new Spawn(plugin, database);
                        spawn.teleportToSpawn(player);
                        return true;
                    } else {
                        Text.sendErrorMessage(player, "home.no-home", lang);
                        return true;
                    }
                }
            }
        } else {
            List<String> homes = database.getPlayerHomes(player.getUniqueId());
            if (homes.size() == 1) {
                return teleportToHome(player, player.getUniqueId(), homes.get(0));
            } else if (homes.size() > 1) {
                return handleHomes(player);
            } else {
                boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
                if (spawnIfNoHome) {
                    Spawn spawn = new Spawn(plugin, database);
                    spawn.teleportToSpawn(player);
                    return true;
                } else {
                    Text.sendErrorMessage(player, "home.no-home", lang);
                    return true;
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
        String homeList = getFormattedHomeList(player, homes);
        if (homes.isEmpty() && !player.hasPermission("allium.home.bed")) {
            boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
            if (spawnIfNoHome) {
                Spawn spawn = new Spawn(plugin, database);
                spawn.teleportToSpawn(player);
            } else {
                Text.sendErrorMessage(player, "home.no-home", lang);
            }
        } else {
            player.sendMessage(lang.get("home.list").replace("{homes}", homeList));
        }
        return true;
    }

    private boolean handleOtherPlayerHomes(Player player, UUID targetUUID, String targetName) {
        List<String> homes = database.getPlayerHomes(targetUUID);
        String homeList = getFormattedHomeList(player, homes);
        if (homes.isEmpty() && !player.hasPermission("allium.home.bed")) {
            boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
            if (spawnIfNoHome) {
                Spawn spawn = new Spawn(plugin, database);
                spawn.teleportToSpawn(player);
                return true;
            } else {
                Text.sendErrorMessage(player, "home.no-home", lang);
                return true;
            }
        } else {
            player.sendMessage(lang.get("home.list-others")
                .replace("{player}", targetName)
                .replace("{homes}", homeList));
        }
        return true;
    }

    private boolean handleDelHome(Player player, String[] args) {
        if (!player.hasPermission("allium.delhome")) {
            Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "delete homes.", true);
            return true;
        }

        if (args.length == 0) {
            List<String> homes = database.getPlayerHomes(player.getUniqueId());
            if (homes.size() == 1) {
                return deleteHome(player, player.getUniqueId(), homes.get(0));
            } else if (homes.size() > 1) {
                String homeList = getFormattedHomeList(player, homes);
                player.sendMessage(lang.get("home.list").replace("{homes}", homeList));
                return handleHomes(player);
            } else {
                boolean spawnIfNoHome = plugin.getConfig().getBoolean("home.spawn-if-no-home", false);
                if (spawnIfNoHome) {
                    Spawn spawn = new Spawn(plugin, database);
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
                Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}", "delete other players' homes.", true);
                return true;
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
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Location home = database.getPlayerHome(targetUUID, homeName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (home == null) {
                    String homeList = getFormattedHomeList(player, database.getPlayerHomes(targetUUID));
                    player.sendMessage(lang.get("home.list").replace("{homes}", homeList));
                    return;
                }
                if (player.hasPermission("core.tpa.nodelay") || teleportDelay <= 0) {
                    savePlayerLocationToBackHistory(player);
                    player.teleport(home);
                    if (!player.hasPermission("core.home.nocooldown")) {
                        setCooldown(player.getUniqueId());
                    }
                    player.sendMessage(lang.get("tp.success")
                        .replace("{name}", "to " + homeName)
                        .replace("{target}", "at " + formatLocation(home)));
                } else {
                    player.sendMessage(lang.get("tp.delay")
                        .replace("{time}", String.valueOf(teleportDelay)));
                    TeleportCancelListener listener = new TeleportCancelListener(plugin, player, player.getLocation(), TELEPORT_LENIENCY);
                    Bukkit.getPluginManager().registerEvents(listener, plugin);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!listener.isCancelled()) {
                            savePlayerLocationToBackHistory(player);
                            player.teleport(home);
                            if (!player.hasPermission("core.home.nocooldown")) {
                                setCooldown(player.getUniqueId());
                            }
                            player.sendMessage(lang.get("tp.success")
                                .replace("{name}", "to " + homeName)
                                .replace("{target}", "at " + formatLocation(home)));
                        }
                        HandlerList.unregisterAll(listener);
                    }, teleportDelay * 20L); // Convert seconds to ticks
                }
            });
        });
        return true;
    }

    private boolean deleteHome(Player player, UUID targetUUID, String homeName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean deleted = database.deletePlayerHome(targetUUID, homeName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (deleted) {
                    player.sendMessage(lang.get("home.remove").replace("{home}", homeName));
                } else {
                    String homeList = getFormattedHomeList(player, database.getPlayerHomes(targetUUID));
                    String target = targetUUID.equals(player.getUniqueId()) ? "your" : targetUUID.toString();
                    player.sendMessage(lang.get("home.not-found")
                        .replace("{home}", homeName)
                        .replace("{target}", target)
                        .replace("{homes}", homeList));
                }
            });
        });
        return true;
    }

    private String getFormattedHomeList(Player player, List<String> homes) {
        List<String> formattedHomes = new ArrayList<>(homes);
        if (player.hasPermission("core.home.bed")) {
            boolean bedAccessible = player.getBedSpawnLocation() != null && isBedAccessible(player.getBedSpawnLocation());
            String bedFormat = bedAccessible ? "bed" : "§mbed§r";
            formattedHomes.add(bedFormat);
        }
        return formattedHomes.isEmpty() ? "§cnone" : "§e" + String.join(", ", formattedHomes);
    }

    private boolean isBedAccessible(Location bedLocation) {
        if (bedLocation == null) {
            return false;
        }
        if (!bedLocation.getChunk().isLoaded()) {
            bedLocation.getChunk().load();
        }
        return bedLocation.getBlock().getType().toString().contains("BED");
    }

    private boolean isValidHomeName(String name) {
        return HOME_NAME_PATTERN.matcher(name).matches();
    }

    private int getMaxHomes(Player player) {
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

    private void savePlayerLocationToBackHistory(Player player) {
        try {
            CommandExecutor cmdExecutor = plugin.getCommand("tp").getExecutor();
            if (cmdExecutor instanceof TP) {
                TP tpCommand = (TP) cmdExecutor;
                java.lang.reflect.Field lastLocationField = TP.class.getDeclaredField("lastLocation");
                lastLocationField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UUID, Location> lastLocation = (Map<UUID, Location>) lastLocationField.get(tpCommand);
                lastLocation.put(player.getUniqueId(), player.getLocation());
                plugin.getLogger().info("[Allium] Saved back location for player " + player.getName() + " when using /home");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Allium] Error saving back location for /home: " + e.getMessage(), e);
        }
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