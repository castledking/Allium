package codes.castled.allium.legacy;

import codes.castled.allium.restart.RestartSettings;
import codes.castled.allium.restart.RestartTiming;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class LegacyAutoRestart {
    private static final String USAGE = "{prefix} &f/ar help &7| &f/ar time &7| &f/ar now [delay] [--dry-run]"
            + " &7| &f/ar stop &7| &f/ar reload &7| &f/ar vote [start|yes|no]";
    private static final List<String> ACTIONS = Arrays.asList("help", "time", "now", "stop", "reload", "vote");
    private static final List<String> VOTES = Arrays.asList("yes", "no", "start");

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyMessageManager messages;
    private final File file;
    private RestartSettings settings;
    private Plan plan;
    private BukkitTask ticker;
    private BukkitTask voteTask;
    private Map<UUID, Boolean> votes;
    private boolean stopping;

    LegacyAutoRestart(JavaPlugin plugin, LegacyConfigManager config, LegacyMessageManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.file = new File(plugin.getDataFolder(), "autorestart.yml");
    }

    boolean reload() {
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            boolean missing = !file.exists();
            if (missing) {
                try (InputStream defaults = plugin.getResource("autorestart.yml")) {
                    if (defaults != null) loaded.load(new InputStreamReader(defaults, StandardCharsets.UTF_8));
                }
                ConfigurationSection legacy = config.raw().getConfigurationSection("auto-restart");
                if (legacy != null) {
                    for (String key : legacy.getKeys(false)) loaded.set(key, legacy.get(key));
                }
            } else {
                loaded.load(file);
            }
            RestartSettings parsed = RestartSettings.read(loaded);
            if (missing) LegacyCompat.saveYamlAtomically(loaded, file);
            settings = parsed;
            scheduleDaily();
            return true;
        } catch (IOException | InvalidConfigurationException | DateTimeException | IllegalArgumentException error) {
            plugin.getLogger().warning("Could not load autorestart.yml: " + error.getMessage());
            return false;
        }
    }

    boolean execute(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if ("status".equals(action)) action = "time";
        if ("delay".equals(action)) action = "now";
        if ("vote".equals(action)) return vote(sender, args.length == 1 ? "yes" : singleArgument(args, 1));
        if (!ACTIONS.contains(action) || (!"now".equals(action) && args.length > 1)) return false;
        if (!allowed(sender, action)) return refuse(sender);
        switch (action) {
            case "help":
                send(sender, "help", USAGE);
                break;
            case "time":
                status(sender);
                break;
            case "now":
                manual(sender, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "stop":
                if (plan == null) status(sender);
                else {
                    cancelPlan();
                    broadcast("cancelled", "{prefix} &aThe scheduled restart was cancelled.");
                }
                break;
            case "reload":
                if (reload()) send(sender, "reloaded", "{prefix} &aReloaded autorestart.yml.");
                else configurationError(sender);
                break;
            default:
                return false;
        }
        return true;
    }

    boolean voteCommand(CommandSender sender, String[] args) {
        if (args.length == 0 && !allowed(sender, "vote")) {
            if (votes == null) status(sender);
            else prompt(sender);
            return true;
        }
        return vote(sender, args.length == 0 ? "start" : singleArgument(args, 0));
    }

    private static String singleArgument(String[] args, int position) {
        return args.length == position + 1 ? args[position].toLowerCase(Locale.ROOT) : "";
    }

    List<String> complete(CommandSender sender, boolean voteCommand, String[] args, String prefix) {
        if (voteCommand && args.length == 1 || !voteCommand && args.length == 2 && "vote".equalsIgnoreCase(args[0])) {
            return VOTES.stream().filter(value -> allowed(sender, "start".equals(value) ? "vote" : "vote.yes"))
                    .filter(value -> value.startsWith(prefix)).collect(Collectors.toList());
        }
        if (voteCommand) return Collections.emptyList();
        if (args.length == 1) {
            return ACTIONS.stream().filter(value -> allowed(sender, "vote".equals(value) ? "vote.yes" : value))
                    .filter(value -> value.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length <= 3 && args.length > 1 && allowed(sender, "now")
                && ("now".equalsIgnoreCase(args[0]) || "delay".equalsIgnoreCase(args[0]))) {
            List<String> values = args.length == 2 ? Arrays.asList("30s", "5m", "--dry-run") : Collections.singletonList("--dry-run");
            return values.stream().filter(value -> value.startsWith(prefix)).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private void manual(CommandSender sender, String[] args) {
        if (settings == null) {
            configurationError(sender);
            return;
        }
        if (stopping) return;
        boolean dryRun = false;
        List<String> time = new ArrayList<>();
        for (String arg : args) {
            if ("--dry-run".equalsIgnoreCase(arg)) dryRun = true;
            else time.add(arg);
        }
        long delay;
        try {
            delay = time.isEmpty() ? 0 : RestartTiming.delaySeconds(String.join("", time));
        } catch (IllegalArgumentException error) {
            send(sender, "invalid-time-format", "{prefix} &cEnter a delay such as 30s, 5m, or 1h.");
            return;
        }
        schedule(Instant.now().plusSeconds(delay), false, dryRun);
        if (dryRun) {
            broadcast("dry-run", "{prefix} &eRestart dry run begins in {time}.", "{time}", RestartTiming.format(delay));
        } else if (delay > 0) {
            broadcast("scheduled", "{prefix} &eServer restart scheduled in {time}.", "{time}", RestartTiming.format(delay));
        }
    }

    private void scheduleDaily() {
        Instant next = settings.next(Instant.now());
        if (next == null) cancelPlan();
        else schedule(next, true, false);
    }

    private void schedule(Instant deadline, boolean preCommands, boolean dryRun) {
        cancelPlan();
        if (stopping) return;
        Plan active = new Plan(deadline, preCommands, dryRun);
        plan = active;
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(active), 1L, 5L);
    }

    private void tick(Plan active) {
        if (plan != active) return;
        long seconds = secondsLeft(active);
        if (active.preCommands && !active.prepared && seconds <= settings.commandDelay()) {
            active.prepared = true;
            for (String command : settings.preCommands()) {
                if (plan != active) return;
                dispatch(command, "pre-restart");
            }
            if (plan != active) return;
        }
        if (seconds == 0) {
            cancelPlan();
            if (active.dryRun) {
                broadcast("dry-complete", "{prefix} &aRestart dry run complete.");
                scheduleDaily();
            } else {
                stopServer();
            }
            return;
        }
        boolean crossed = settings.countdown().stream()
                .anyMatch(value -> value > 0 && value >= seconds && value < active.lastSeconds);
        active.lastSeconds = seconds;
        if (crossed) {
            broadcast("countdown", "{prefix} &eServer restarting in {time} second{plural}.",
                    "{time}", Long.toString(seconds), "{plural}", seconds == 1 ? "" : "s");
        }
    }

    private void stopServer() {
        stopping = true;
        closeVote();
        broadcast("warning", "{prefix} &eThe server is restarting now.");
        if (settings.save()) dispatch("minecraft:save-all flush", "pre-restart save");
        if (settings.finalMessage()) {
            broadcast("server-stopped-message", "{prefix} &7The server is shutting down for restart.");
        }
        String kick = messages.resolveColored("autorestart.kick-message", "&eThe server is restarting. Please rejoin in a moment.");
        for (Player player : new ArrayList<>(plugin.getServer().getOnlinePlayers())) {
            try {
                player.kickPlayer(kick);
            } catch (RuntimeException error) {
                plugin.getLogger().warning("Could not send the restart kick to " + player.getName() + ".");
            }
        }
        plugin.getServer().shutdown();
    }

    private void dispatch(String input, String context) {
        String command = input.trim();
        if (command.startsWith("/")) command = command.substring(1);
        try {
            if (command.isEmpty() || plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)) return;
            plugin.getLogger().warning("A " + context + " command was not handled.");
        } catch (RuntimeException error) {
            plugin.getLogger().warning("A " + context + " command failed: " + error.getClass().getSimpleName());
        }
    }

    private boolean vote(CommandSender sender, String choice) {
        if (!VOTES.contains(choice)) return false;
        if ("start".equals(choice)) {
            if (!allowed(sender, "vote")) return refuse(sender);
            if (settings == null) configurationError(sender);
            else if (votes != null || stopping) send(sender, "vote-already-active", "{prefix} &cA restart vote is already active.");
            else {
                votes = new HashMap<>();
                voteTask = plugin.getServer().getScheduler().runTaskLater(plugin, (Runnable) this::finishVote, 1_200L);
                broadcast("vote-prompt", "{prefix} &eVote on a server restart with /voterestart yes or /voterestart no.");
            }
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.player-only", "{prefix} &cThis command can only be used by players.");
            return true;
        }
        if (!allowed(sender, "vote.yes")) return refuse(sender);
        if (votes == null) send(sender, "vote-none-active", "{prefix} &7No restart vote is active.");
        else if (votes.putIfAbsent(((Player) sender).getUniqueId(), "yes".equals(choice)) != null) {
            send(sender, "vote-duplicate", "{prefix} &cYou already voted.");
        } else {
            send(sender, "vote-cast", "{prefix} &aYour vote was counted: {vote}.", "{vote}", choice);
        }
        return true;
    }

    private void finishVote() {
        if (votes == null) return;
        long yes = votes.values().stream().filter(Boolean::booleanValue).count();
        long no = votes.size() - yes;
        closeVote();
        if (yes > no) {
            broadcast("vote-passed", "{prefix} &aRestart vote passed ({yes} yes, {no} no). Restarting within two minutes.",
                    "{yes}", Long.toString(yes), "{no}", Long.toString(no));
            if (plan == null || plan.dryRun || secondsLeft(plan) > 120) schedule(Instant.now().plusSeconds(120), false, false);
        } else {
            broadcast("vote-failed", "{prefix} &7Restart vote failed ({yes} yes, {no} no).",
                    "{yes}", Long.toString(yes), "{no}", Long.toString(no));
        }
    }

    private void closeVote() {
        if (voteTask != null) voteTask.cancel();
        voteTask = null;
        votes = null;
    }

    private void cancelPlan() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        plan = null;
    }

    void close() {
        cancelPlan();
        closeVote();
    }

    private void status(CommandSender sender) {
        if (plan == null) send(sender, "no-restart-scheduled", "{prefix} &7No restart is scheduled.");
        else send(sender, "time-remaining", "{prefix} &7Next restart in &f{time}&7.",
                "{time}", RestartTiming.format(secondsLeft(plan)));
    }

    private void prompt(CommandSender sender) {
        send(sender, "vote-prompt", "{prefix} &eVote on a server restart with /voterestart yes or /voterestart no.");
    }

    private static boolean allowed(CommandSender sender, String permission) {
        return sender.hasPermission("allium.autorestart." + permission) || sender.hasPermission("allium.admin");
    }

    private boolean refuse(CommandSender sender) {
        messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
        return true;
    }

    private void configurationError(CommandSender sender) {
        send(sender, "config-error", "{prefix} &cCould not load autorestart.yml. Check the server log.");
    }

    private void send(CommandSender sender, String key, String fallback, String... replacements) {
        messages.send(sender, "autorestart." + key, fallback, replacements);
    }

    private void broadcast(String key, String fallback, String... replacements) {
        for (Player player : plugin.getServer().getOnlinePlayers()) send(player, key, fallback, replacements);
        send(plugin.getServer().getConsoleSender(), key, fallback, replacements);
    }

    private static long secondsLeft(Plan active) {
        return RestartTiming.secondsLeft(Instant.now(), active.deadline);
    }

    private static final class Plan {
        private final Instant deadline;
        private final boolean preCommands;
        private final boolean dryRun;
        private long lastSeconds;
        private boolean prepared;

        private Plan(Instant deadline, boolean preCommands, boolean dryRun) {
            this.deadline = deadline;
            this.preCommands = preCommands;
            this.dryRun = dryRun;
            this.lastSeconds = secondsLeft(this);
        }
    }
}
