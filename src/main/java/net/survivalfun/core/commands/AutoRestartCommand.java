package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Objects;

public class AutoRestartCommand implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final Lang lang;
    private int taskId = -1;
    private long restartTime = -1;
    private boolean restartScheduled = false;
    private final List<Long> restartTimes = new ArrayList<>();
    private final List<String> preRestartCommands = new ArrayList<>();
    private final List<Integer> countdownTimes = new ArrayList<>(Arrays.asList(60, 30, 15, 10, 5, 4, 3, 2, 1));

    public AutoRestartCommand(PluginStart plugin) {
        this.plugin = Objects.requireNonNull(plugin, "PluginStart cannot be null");
        this.lang = plugin.getLangManager();
        try {
            loadConfig();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load AutoRestart configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void loadConfig() {
        try {
            // Clear existing data
            restartTimes.clear();
            preRestartCommands.clear();
            
            // Load restart times from config (in minutes)
            List<String> times = plugin.getConfig().getStringList("auto-restart.times");
            if (times != null) {
                for (String time : times) {
                    try {
                        if (time != null && !time.trim().isEmpty()) {
                            restartTimes.add(parseTimeString(time) * 60 * 1000); // Convert to milliseconds
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid time format in auto-restart.times: " + time);
                    }
                }
            }
            
            // Load pre-restart commands
            List<String> commands = plugin.getConfig().getStringList("auto-restart.pre-commands");
            if (commands != null) {
                preRestartCommands.addAll(commands);
            }
            
            // Load countdown times
            List<Integer> countdowns = plugin.getConfig().getIntegerList("auto-restart.countdown-times");
            if (countdowns != null && !countdowns.isEmpty()) {
                countdownTimes.clear();
                countdownTimes.addAll(countdowns);
                countdownTimes.sort(Collections.reverseOrder());
            } else {
                // Default countdown times if not specified in config
                countdownTimes.clear();
                countdownTimes.addAll(Arrays.asList(60, 30, 15, 10, 5, 4, 3, 2, 1));
            }
            
            // Schedule next restart if enabled
            if (plugin.getConfig().getBoolean("auto-restart.enabled", false)) {
                scheduleNextRestart();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load AutoRestart configuration: " + e.getMessage());
            e.printStackTrace();
            // Initialize with safe defaults if config loading fails
            countdownTimes.clear();
            countdownTimes.addAll(Arrays.asList(60, 30, 15, 10, 5, 4, 3, 2, 1));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        if (!hasPermission(sender, "allium.autorestart." + args[0].toLowerCase()) && !hasPermission(sender, "allium.admin")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "use /{cmd}", "access auto-restart commands", true);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "time":
            case "status":
                sendNextRestartTime(sender);
                break;
                
            case "now":
            case "delay":
                if (args.length < 2) {
                    Text.sendErrorMessage(sender, "invalid-usage", lang, "/ar now <time>");
                    return true;
                }
                try {
                    long delay = parseTimeString(args[1]) * 1000; // Convert to milliseconds
                    scheduleRestart(delay, true);
                    Text.broadcast("lang:autorestart.scheduled", "time", Text.formatTime((int)(delay / 1000)));
                } catch (IllegalArgumentException e) {
                    Text.sendErrorMessage(sender, "invalid-time-format", lang);
                }
                break;
                
            case "stop":
                if (restartScheduled) {
                    cancelRestart();
                    Text.broadcast("lang:autorestart.cancelled");
                } else {
                    Text.sendErrorMessage(sender, "autorestart.not-scheduled", lang);
                }
                break;      
            default:
                sendHelp(sender);
                break;
        }
        
        return true;
    }

    private void sendHelp(CommandSender sender) {
        String header = lang.get("autorestart.help-header");
        sender.sendMessage(header);
        
        if (hasPermission(sender, "allium.autorestart.reload") || hasPermission(sender, "allium.admin")) {
            sender.sendMessage(lang.get("autorestart.help-reload"));
        }
        
        sender.sendMessage(lang.get("autorestart.help-time"));
        
        if (hasPermission(sender, "allium.autorestart.now") || hasPermission(sender, "allium.admin")) {
            sender.sendMessage(lang.get("autorestart.help-now"));
        }
        
        if (hasPermission(sender, "allium.autorestart.stop") || hasPermission(sender, "allium.admin")) {
            sender.sendMessage(lang.get("autorestart.help-stop"));
        }
        
        if (restartScheduled) {
            sender.sendMessage(lang.get("autorestart.next-restart")
                    .replace("{time}", Text.formatTime((int)((restartTime - System.currentTimeMillis()) / 1000))));
        }
    }

    private void sendNextRestartTime(CommandSender sender) {
        if (restartScheduled) {
            long timeLeft = (restartTime - System.currentTimeMillis()) / 1000;
            sender.sendMessage(lang.get("autorestart.time-remaining")
                    .replace("{time}", Text.formatTime((int)timeLeft)));
        } else {
            sender.sendMessage(lang.get("autorestart.no-restart-scheduled"));
        }
    }

    private void scheduleNextRestart() {
        if (restartTimes.isEmpty()) {
            plugin.getLogger().warning("No restart times configured in config.yml");
            return;
        }
        
        long now = System.currentTimeMillis();
        long nextRestart = -1;
        
        // Find the next restart time after now
        for (long restartTime : restartTimes) {
            long nextTime = (restartTime - (now % (24 * 60 * 60 * 1000)));
            if (nextTime < 0) {
                nextTime += 24 * 60 * 60 * 1000; // Add a day if the time has already passed today
            }
            
            if (nextRestart == -1 || nextTime < nextRestart) {
                nextRestart = nextTime;
            }
        }
        
        if (nextRestart > 0) {
            scheduleRestart(nextRestart, false);
            plugin.getLogger().info("Next restart scheduled in " + Text.formatTime((int)(nextRestart / 1000)));
        }
    }

    private void scheduleRestart(long delay, boolean force) {
        // Cancel any existing restart task
        cancelRestart();
        
        restartTime = System.currentTimeMillis() + delay;
        restartScheduled = true;
        
        // Schedule the restart
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            long timeLeft = (restartTime - System.currentTimeMillis()) / 1000;
            
            // Check for countdown times
            if (countdownTimes.contains((int) timeLeft)) {
                String message = lang.get("autorestart.countdown")
                        .replace("{time}", String.valueOf(timeLeft))
                        .replace("\n", " ");
                Bukkit.broadcastMessage(message);
            }
            
            // Check for restart time
            if (timeLeft <= 0) {
                executeRestart();
            }
        }, 0L, 20L);
        
        // Schedule pre-commands
        if (preRestartCommands.size() > 0) {
            long commandDelay = Math.max(0, delay - (plugin.getConfig().getInt("auto-restart.command-delay", 30) * 1000L));
            Bukkit.getScheduler().runTaskLater(plugin, this::executePreRestartCommands, commandDelay / 50);
        }
    }

    private void cancelRestart() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        restartScheduled = false;
        restartTime = -1;
    }

    private void executePreRestartCommands() {
        for (String cmd : preRestartCommands) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to execute pre-restart command: " + cmd);
                e.printStackTrace();
            }
        }
    }

    private volatile boolean isShuttingDown = false;
    private final Object shutdownLock = new Object();
    
    public void shutdown() {
        cancelRestart();
        // Add any additional cleanup here
        isShuttingDown = true;
    }
    
    private void executeRestart() {
        if (isShuttingDown) {
            plugin.getLogger().warning("Shutdown already in progress");
            return;
        }
        
        synchronized (shutdownLock) {
            isShuttingDown = true;
            cancelRestart();
            
            // Remove shutdown hook as it can cause issues with the server's shutdown process
            // Runtime.getRuntime().addShutdownHook(new Thread(this::finalCleanup, "ShutdownCleanup"));
            
            // Broadcast initial warning
            String warningMsg = lang.get("autorestart.warning");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(warningMsg);
            }
            
            // Save all player data and kick players
            Bukkit.getOnlinePlayers().forEach(player -> {
                try {
                    player.saveData();
player.kick(Component.text(lang.get("autorestart.kick-message")));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save data for " + player.getName() + ": " + e.getMessage());
                }
            });
            
            // Save all worlds with retry logic
            saveAllWorlds();
            
            // Execute pre-commands if any
            executePreRestartCommands();
            
            // Schedule the actual shutdown with a delay to ensure everything is saved
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    // Close plugin resources if needed
                    closePluginResources();
                    
                    // Final save of all worlds
                    saveAllWorlds();
                    
                    // Shutdown the server
                    Bukkit.shutdown();
                } catch (Exception e) {
                    plugin.getLogger().severe("Error during shutdown: " + e.getMessage());
                    e.printStackTrace();
                    // Force shutdown if normal shutdown fails
                    System.exit(0);
                }
            }, 40L); // 2 second delay (40 ticks) for final saves
        }
    }
    
    private void saveAllWorlds() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            try {
                plugin.getLogger().info("Saving world: " + world.getName());
                world.save();
                // Save players in this world
                world.getPlayers().forEach(Player::saveData);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save world " + world.getName() + ": " + e.getMessage());
            }
        }
    }
    
    private void closePluginResources() {
        try {
            // Minimal cleanup - let HikariCP handle database connections
            // No need to explicitly close anything here as HikariCP has its own shutdown hook
        } catch (Throwable t) {
            // Use error level for shutdown issues
            plugin.getLogger().severe("Error during resource cleanup: " + t.getMessage());
            t.printStackTrace(); // Print full stack trace for debugging
        }
    }
    
    private void finalCleanup() {
        // This runs in a shutdown hook
        try {
            plugin.getLogger().info("Performing final cleanup...");
            // Any final cleanup that must run before JVM exits
        } catch (Exception e) {
            // Use stderr as logger might not be available
            System.err.println("Error in final cleanup: " + e.getMessage());
        }
    }

    private long parseTimeString(String timeStr) throws IllegalArgumentException {
        timeStr = timeStr.trim().toLowerCase();
        
        try {
            // Handle HH:mm format (e.g., "04:00", "16:30")
            if (timeStr.matches("^\\d{1,2}:\\d{2}$")) {
                String[] parts = timeStr.split(":");
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                
                if (hours < 0 || hours > 23) {
                    throw new IllegalArgumentException("Hours must be between 0 and 23");
                }
                if (minutes < 0 || minutes > 59) {
                    throw new IllegalArgumentException("Minutes must be between 0 and 59");
                }
                
                return (hours * 3600L) + (minutes * 60L);
            }
            // Handle time with units (e.g., "30s", "5m", "2h", "1d")
            else if (timeStr.endsWith("s")) {
                return Long.parseLong(timeStr.substring(0, timeStr.length() - 1));
            } else if (timeStr.endsWith("m")) {
                return Long.parseLong(timeStr.substring(0, timeStr.length() - 1)) * 60;
            } else if (timeStr.endsWith("h")) {
                return Long.parseLong(timeStr.substring(0, timeStr.length() - 1)) * 60 * 60;
            } else if (timeStr.endsWith("d")) {
                return Long.parseLong(timeStr.substring(0, timeStr.length() - 1)) * 24 * 60 * 60;
            } 
            // Default to seconds if no unit is specified
            else {
                return Long.parseLong(timeStr);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid time format: " + timeStr + ". Expected format: HH:mm or number with unit (s/m/h/d)");
        }
    }


    private boolean hasPermission(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || sender.isOp();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            
            if (hasPermission(sender, "allium.autorestart.help") || hasPermission(sender, "allium.admin")) {
                completions.add("help");
            }
            if (hasPermission(sender, "allium.autorestart.time") || hasPermission(sender, "allium.admin")) {
                completions.add("time");
                completions.add("status");
            }
            if (hasPermission(sender, "allium.autorestart.now") || hasPermission(sender, "allium.admin")) {
                completions.add("now");
                completions.add("delay");
            }
            if (hasPermission(sender, "allium.autorestart.stop") || hasPermission(sender, "allium.admin")) {
                completions.add("stop");
            }
            
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("now") || args[0].equalsIgnoreCase("delay"))) {
            return Arrays.asList("30s", "1m", "5m", "10m", "30m", "1h");
        }
        
        return Collections.emptyList();
    }
}
