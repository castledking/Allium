package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.util.SchedulerAdapter;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Objects;

public class AutoRestartCommand implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;
    private final Lang lang;
    private SchedulerAdapter.TaskHandle countdownTask;
    private long restartTime = -1;
    private boolean restartScheduled = false;
    private boolean skipPreCommands = false; // true when /ar now - skip pre-commands
    private boolean dryRun = false; // true when --dry-run - run countdown/pre-commands but don't restart
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
                try {
                    boolean dryRunFlag = false;
                    String timeArg = null;
                    for (int i = 1; i < args.length; i++) {
                        if ("--dry-run".equalsIgnoreCase(args[i])) {
                            dryRunFlag = true;
                        } else if (timeArg == null) {
                            timeArg = args[i];
                        }
                    }
                    long delay;
                    if (timeArg == null || timeArg.isBlank() || timeArg.equalsIgnoreCase("now")) {
                        // /ar now with no time = restart immediately
                        delay = 0;
                    } else {
                        delay = parseTimeString(timeArg.trim()) * 1000; // Convert to milliseconds
                    }
                    scheduleRestart(delay, true, dryRunFlag);
                    String msg = dryRunFlag
                        ? "Dry run: restart scheduled in " + Text.formatTime((int)(delay / 1000)) + " (server will NOT restart)"
                        : lang.get("autorestart.scheduled").replace("{time}", Text.formatTime((int)(delay / 1000)));
                    Text.broadcast(msg);
                } catch (IllegalArgumentException e) {
                    String errMsg = lang.getRaw("autorestart.invalid-time-format");
                    if (errMsg != null && !errMsg.contains("Missing translation")) {
                        Text.sendErrorMessage(sender, "autorestart.invalid-time-format", lang);
                    } else {
                        sender.sendMessage(Text.parseColors("&cInvalid time format. Use: 1 (seconds), 30s, 5m, 1h"));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("AutoRestart error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    sender.sendMessage(Text.parseColors("&cError: " + e.getMessage()));
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
            scheduleRestart(nextRestart, false, false);
            plugin.getLogger().info("Next restart scheduled in " + Text.formatTime((int)(nextRestart / 1000)));
        }
    }

    private void scheduleRestart(long delay, boolean force, boolean dryRunMode) {
        // Cancel any existing restart task
        cancelRestart();
        
        restartTime = System.currentTimeMillis() + delay;
        restartScheduled = true;
        skipPreCommands = force; // /ar now = force = true, skip pre-commands
        dryRun = dryRunMode;
        
        // Schedule the restart (use SchedulerAdapter for Folia/Canvas compatibility)
        // Folia/Canvas requires initialDelayTicks > 0; use 1 tick minimum
        long initialDelayTicks = 1L;
        long periodTicks = 20L;
        countdownTask = SchedulerAdapter.runTimer(() -> {
            long timeLeft = (restartTime - System.currentTimeMillis()) / 1000;
            
            // Check for countdown times
            if (countdownTimes.contains((int) timeLeft)) {
                String plural = (timeLeft == 1) ? "" : lang.get("autorestart.plural");
                String message = lang.get("autorestart.countdown")
                        .replace("{time}", String.valueOf(timeLeft))
                        .replace("{plural}", plural)
                        .replace("\n", " ");
                Bukkit.broadcastMessage(net.survivalfun.core.managers.core.Text.parseColors(message));
            }
            
            // Check for restart time
            if (timeLeft <= 0 && !isShuttingDown) {
                executeRestart();
            }
        }, initialDelayTicks, periodTicks);
        
        // Schedule pre-commands (skip when /ar now)
        if (!skipPreCommands && !preRestartCommands.isEmpty()) {
            long commandDelayTicks = Math.max(0, (delay - (plugin.getConfig().getInt("auto-restart.command-delay", 30) * 1000L)) / 50);
            SchedulerAdapter.runLater(this::executePreRestartCommands, commandDelayTicks);
        }
    }

    private void cancelRestart() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
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
            return;
        }
        isShuttingDown = true;
        cancelRestart(); // Stop repeating task immediately to prevent re-entry
        
        synchronized (shutdownLock) {
            boolean wasDryRun = dryRun;
            
            if (wasDryRun) {
                // Dry run: run pre-commands and broadcast, but do NOT kick/save/shutdown
                String dryRunMsg = net.survivalfun.core.managers.core.Text.parseColors("&e[Dry Run] &fRestart sequence complete - server would have restarted.");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(dryRunMsg);
                }
                Bukkit.getConsoleSender().sendMessage("[Allium] Dry run complete - server was NOT restarted.");
                if (!skipPreCommands) {
                    executePreRestartCommands();
                }
                return;
            }
            
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
            
            // Execute pre-commands if any (skip when /ar now)
            if (!skipPreCommands) {
                executePreRestartCommands();
            }
            
            // Schedule the actual shutdown with a delay to ensure everything is saved
            SchedulerAdapter.runLater(() -> {
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

    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d+)\\s*([smhd])?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HHMM_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    // Fallback: extract leading digits + optional trailing unit
    private static final Pattern LOOSE_PATTERN = Pattern.compile("(\\d+)\\s*([smhd])?", Pattern.CASE_INSENSITIVE);

    private long parseTimeString(String timeStr) throws IllegalArgumentException {
        if (timeStr == null || (timeStr = timeStr.trim()).isEmpty()) {
            throw new IllegalArgumentException("Time cannot be empty");
        }
        // Normalize: remove any non-printable chars, collapse spaces
        timeStr = timeStr.replaceAll("[\\s\\p{C}]", " ").trim();
        String lower = timeStr.toLowerCase(Locale.ROOT);

        // HH:mm format (e.g., "04:00", "16:30")
        Matcher hhmm = HHMM_PATTERN.matcher(lower);
        if (hhmm.matches()) {
            int hours = Integer.parseInt(hhmm.group(1));
            int minutes = Integer.parseInt(hhmm.group(2));
            if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
                throw new IllegalArgumentException("Invalid HH:mm");
            }
            return (hours * 3600L) + (minutes * 60L);
        }

        // Strict: Number + optional unit (1, 1s, 30s, 5m, 2h, 1d)
        Matcher m = TIME_PATTERN.matcher(lower);
        if (m.matches()) {
            return parseTimeFromMatcher(m);
        }
        // Loose fallback: extract number and unit from start of string
        Matcher loose = LOOSE_PATTERN.matcher(lower);
        if (loose.find() && loose.start() == 0) {
            return parseTimeFromMatcher(loose);
        }
        throw new IllegalArgumentException("Invalid time format: " + timeStr + ". Use: 1, 30s, 5m, 1h, etc.");
    }

    private long parseTimeFromMatcher(Matcher m) {
        long num = Long.parseLong(m.group(1));
        String unit = m.group(2);
        if (unit == null || unit.isEmpty()) {
            return num; // plain number = seconds
        }
        return switch (Character.toLowerCase(unit.charAt(0))) {
            case 's' -> num;
            case 'm' -> num * 60;
            case 'h' -> num * 3600;
            case 'd' -> num * 86400;
            default -> num;
        };
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
        } else if (args.length >= 2 && (args[0].equalsIgnoreCase("now") || args[0].equalsIgnoreCase("delay"))) {
            List<String> completions = new ArrayList<>(Arrays.asList("30s", "1m", "5m", "10m", "30m", "1h", "--dry-run"));
            String partial = args.length == 2 ? args[1] : (args.length == 3 ? args[2] : "");
            if (!partial.isEmpty()) {
                return completions.stream()
                        .filter(s -> s.toLowerCase().startsWith(partial.toLowerCase()))
                        .collect(Collectors.toList());
            }
            return completions;
        }
        
        return Collections.emptyList();
    }
}
