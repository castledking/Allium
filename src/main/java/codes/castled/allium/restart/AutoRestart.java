package codes.castled.allium.restart;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.config.YamlFiles;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.text.TextFormatter;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class AutoRestart implements SpecCommandHandler, AutoCloseable {
    private static final String PERMISSION = "allium.autorestart.";
    private static final List<String> VOTES = List.of("start", "yes", "no");
    private static final String USAGE = "/ar <help|time|now|stop|reload|vote>";
    private static final Pattern DELAY = Pattern.compile("(\\d+)([smhd]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK_DELAY = Pattern.compile("(\\d{1,2}):([0-5]\\d)");

    record Plan(Instant deadline, boolean preCommands, boolean dryRun) {}

    private final PluginStart plugin;
    private final Object stateLock = new Object();
    private final AtomicBoolean shutdownTriggered = new AtomicBoolean();
    private volatile RestartSettings settings;
    private volatile Plan plan;
    private TaskHandle task = TaskHandle.NONE;
    private TaskHandle voteTask = TaskHandle.NONE;
    private TaskHandle shutdownFallback = TaskHandle.NONE;
    private long lastSeconds;
    private boolean prepared;
    private boolean discordWarned;
    private volatile boolean stopping;
    private Map<UUID, Boolean> votes;

    public AutoRestart(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.AUTORESTART;
    }

    public SpecCommandHandler voteCommand() {
        return new SpecCommandHandler() {
            @Override public CommandSpec spec() { return CoreCommandSpecs.VOTERESTART; }
            @Override public void execute(BukkitCommandContext context) {
                String[] args = context.args();
                if (args.length == 0 && !allowed(context.sender(), "vote")) {
                    boolean activeVote;
                    synchronized (stateLock) {
                        activeVote = votes != null;
                    }
                    if (!activeVote) status(context.sender());
                    else send(context.sender(), Messages.RESTART_VOTE_PROMPT);
                } else vote(context, args.length == 0 ? new String[]{"start"} : args);
            }
        };
    }

    public boolean reload() {
        try {
            var path = plugin.getDataFolder().toPath().resolve("autorestart.yml");
            var config = new YamlConfiguration();
            if (Files.exists(path)) {
                config.load(path.toFile());
            } else {
                try (var reader = new InputStreamReader(plugin.getResource("autorestart.yml"), StandardCharsets.UTF_8)) {
                    config.load(reader);
                }
                ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("auto-restart");
                if (legacy != null) {
                    for (String key : legacy.getKeys(false)) config.set(key, legacy.get(key));
                }
                RestartSettings.read(config);
                plugin.saveResource("autorestart.yml", false);
                YamlFiles.normalize(path, config);
            }
            RestartSettings updated = RestartSettings.read(config);
            settings = updated;
            scheduleDaily();
            return true;
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            plugin.logger().warning("Could not load autorestart.yml: " + error.getMessage());
            return false;
        }
    }

    @Override
    public void execute(BukkitCommandContext context) {
        var args = context.args();
        var sender = context.sender();
        var action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("vote")) {
            var rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            vote(context, rest.length == 0 ? new String[]{"yes"} : rest);
            return;
        }
        if (action.equals("status")) action = "time";
        if (action.equals("delay")) action = "now";
        if (!List.of("help", "time", "now", "stop", "reload").contains(action)) {
            context.sendInvalidArgs(USAGE);
            return;
        }
        if (!allowed(sender, action)) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        if (!action.equals("now") && args.length > 1) {
            context.sendInvalidArgs(USAGE);
            return;
        }
        switch (action) {
            case "help" -> send(sender, Messages.RESTART_HELP);
            case "time" -> status(sender);
            case "now" -> manual(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "stop" -> {
                if (plan == null) send(sender, Messages.RESTART_NONE);
                else {
                    cancel();
                    broadcast(Messages.RESTART_CANCELLED);
                }
            }
            case "reload" -> send(sender, reload() ? Messages.RESTART_RELOADED : Messages.RESTART_CONFIG_ERROR);
            default -> throw new IllegalStateException(action);
        }
    }

    private void manual(CommandSender sender, String[] args) {
        if (settings == null) {
            send(sender, Messages.RESTART_CONFIG_ERROR);
            return;
        }
        boolean dryRun = false;
        var timeArgs = new ArrayList<String>();
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--dry-run")) dryRun = true;
            else timeArgs.add(arg);
        }
        final long delay;
        try {
            delay = timeArgs.isEmpty() ? 0 : delaySeconds(String.join("", timeArgs));
        } catch (IllegalArgumentException invalid) {
            send(sender, Messages.RESTART_INVALID_TIME);
            return;
        }
        schedule(new Plan(Instant.now().plusSeconds(delay), false, dryRun));
        if (dryRun || delay > 0) broadcast(dryRun ? Messages.RESTART_DRY_RUN : Messages.RESTART_SCHEDULED,
                "{time}", format(delay));
    }

    static long delaySeconds(String raw) {
        if (raw.equalsIgnoreCase("now")) return 0;
        var clock = CLOCK_DELAY.matcher(raw);
        if (clock.matches()) {
            int hour = Integer.parseInt(clock.group(1));
            if (hour < 24) return hour * 3600L + Integer.parseInt(clock.group(2)) * 60L;
        }
        var matcher = DELAY.matcher(raw);
        if (!matcher.matches()) throw new IllegalArgumentException("invalid delay");
        long number = Long.parseLong(matcher.group(1));
        long factor = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "m" -> 60;
            case "h" -> 3_600;
            case "d" -> 86_400;
            default -> 1;
        };
        if (number > 31_536_000L / factor) throw new IllegalArgumentException("delay too large");
        return number * factor;
    }

    private void scheduleDaily() {
        Instant next = settings.next(Instant.now());
        if (next == null) cancel();
        else schedule(new Plan(next, true, false));
    }

    private void schedule(Plan next) {
        TaskHandle previous;
        synchronized (stateLock) {
            previous = task;
            task = TaskHandle.NONE;
            plan = null;
            if (stopping) {
                previous.cancel();
                return;
            }
            plan = next;
            prepared = false;
            discordWarned = false;
            lastSeconds = secondsLeft(next);
            TaskHandle created = plugin.getScheduler().repeatOnServer(() -> tick(next), 50, 250);
            task = created != null ? created : TaskHandle.NONE;
        }
        previous.cancel();
    }

    boolean tick(Plan active) {
        RestartSettings currentSettings = settings;
        long seconds = secondsLeft(active);
        boolean runPreCommands = false;
        synchronized (stateLock) {
            if (plan != active || currentSettings == null) return false;
            if (active.preCommands() && !prepared && seconds <= currentSettings.commandDelay()) {
                prepared = true;
                runPreCommands = true;
            }
        }
        if (runPreCommands) {
            for (String command : currentSettings.preCommands()) {
                if (plan != active) return false;
                try {
                    if (!dispatch(command)) plugin.logger().warning("A pre-restart command was not handled.");
                } catch (RuntimeException failed) {
                    plugin.logger().warning("A pre-restart command failed: " + failed.getClass().getSimpleName());
                }
            }
            if (plan != active) return false;
        }
        if (seconds == 0) {
            boolean shouldComplete;
            synchronized (stateLock) {
                shouldComplete = plan == active;
                if (shouldComplete) {
                    plan = null;
                    task.cancel();
                    task = TaskHandle.NONE;
                }
            }
            if (!shouldComplete) return false;
            if (active.dryRun()) {
                broadcast(Messages.RESTART_DRY_COMPLETE);
                scheduleDaily();
            } else stopServer();
            return false;
        }
        long previous;
        boolean sendDiscord = false;
        synchronized (stateLock) {
            if (plan != active) return false;
            previous = lastSeconds;
            lastSeconds = seconds;
            if (!active.dryRun() && !discordWarned && currentSettings.discordWarning() > 0
                    && seconds <= currentSettings.discordWarning()) {
                discordWarned = true;
                sendDiscord = true;
            }
        }
        var countdown = currentSettings.countdown().stream().filter(n -> n > 0 && n >= seconds && n < previous)
                .min(Integer::compareTo);
        if (countdown.isPresent()) {
            broadcast(Messages.RESTART_COUNTDOWN, "{time}", Long.toString(seconds), "{plural}", seconds == 1 ? "" : "s");
        }
        if (sendDiscord) {
            discord("Server will restart in " + format(seconds) + ".");
        }
        return true;
    }

    private void stopServer() {
        synchronized (stateLock) {
            if (stopping) return;
            stopping = true;
        }
        closeVote();
        broadcast(Messages.RESTART_NOW);
        discord("Server is restarting now.");
        RestartSettings currentSettings = settings;
        try {
            if (currentSettings != null && currentSettings.save()) dispatch("minecraft:save-all flush");
        } catch (RuntimeException error) {
            plugin.logger().warning("Pre-restart save command failed. The server will save during shutdown.");
        }
        if (currentSettings != null && currentSettings.finalMessage()) broadcast(Messages.RESTART_STOPPED);
        String kick = TextFormatter.parse(plugin.getMessageManager().resolve(Messages.RESTART_KICK)).toLegacy();
        List<Player> online = List.copyOf(plugin.getServer().getOnlinePlayers());
        if (!BukkitServerThread.isFolia() || plugin.getScheduler() == null || online.isEmpty()) {
            for (Player player : online) {
                try {
                    player.kickPlayer(kick);
                } catch (RuntimeException error) {
                    plugin.logger().warning("Could not send the restart kick to " + player.getName() + ".");
                }
            }
            triggerShutdown();
            return;
        }
        synchronized (stateLock) {
            TaskHandle fallback = plugin.getScheduler().runOnServerLater(this::triggerShutdown, 1_000L);
            shutdownFallback = fallback != null ? fallback : TaskHandle.NONE;
        }
        AtomicInteger remaining = new AtomicInteger(online.size() + 1);
        Runnable done = () -> {
            if (remaining.decrementAndGet() == 0) {
                UtilitySupport.onGlobal(plugin, this::triggerShutdown);
            }
        };
        for (Player player : online) {
            Runnable kickTask = () -> {
                try {
                    if (player.isOnline()) {
                        Player current = plugin.getServer().getPlayer(player.getUniqueId());
                        if (current == null || current == player) {
                            player.kickPlayer(kick);
                        }
                    }
                } catch (RuntimeException error) {
                    plugin.logger().warning("Could not send the restart kick to " + player.getName() + ".");
                } finally {
                    done.run();
                }
            };
            var owner = BukkitServerThread.entity(plugin, player);
            if (owner.isServerThread()) {
                kickTask.run();
            } else {
                TaskHandle handle = plugin.getScheduler().runOn(owner, kickTask, done, 0L);
                if (handle == TaskHandle.NONE) {
                    done.run();
                }
            }
        }
        done.run();
    }

    private void triggerShutdown() {
        if (!shutdownTriggered.compareAndSet(false, true)) return;
        TaskHandle fallback;
        synchronized (stateLock) {
            fallback = shutdownFallback;
            shutdownFallback = TaskHandle.NONE;
        }
        fallback.cancel();
        plugin.getServer().shutdown();
    }

    private boolean dispatch(String command) {
        String stripped = command.strip().replaceFirst("^/", "");
        return !stripped.isEmpty() && plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), stripped);
    }

    private void vote(BukkitCommandContext context, String[] args) {
        var sender = context.sender();
        if (args.length != 1 || !VOTES.contains(args[0].toLowerCase(Locale.ROOT))) {
            context.sendInvalidArgs(context.spec().selfUsage());
            return;
        }
        String choice = args[0].toLowerCase(Locale.ROOT);
        if (choice.equals("start")) {
            if (!allowed(sender, "vote")) {
                send(sender, Messages.NO_PERMISSION);
                return;
            }
            if (settings == null) {
                send(sender, Messages.RESTART_CONFIG_ERROR);
                return;
            }
            boolean started;
            synchronized (stateLock) {
                if (votes != null || stopping) {
                    started = false;
                } else {
                    votes = new HashMap<>();
                    TaskHandle handle = plugin.getScheduler().runOnServerLater(this::finishVote, 60_000);
                    voteTask = handle != null ? handle : TaskHandle.NONE;
                    started = true;
                }
            }
            if (!started) send(sender, Messages.RESTART_VOTE_ACTIVE);
            else broadcast(Messages.RESTART_VOTE_PROMPT);
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, Messages.PLAYER_ONLY);
            return;
        }
        if (!allowed(sender, "vote.yes")) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Messages result;
        synchronized (stateLock) {
            if (votes == null) result = Messages.RESTART_VOTE_NONE;
            else if (votes.putIfAbsent(player.getUniqueId(), choice.equals("yes")) != null) result = Messages.RESTART_VOTE_DUPLICATE;
            else result = Messages.RESTART_VOTE_CAST;
        }
        if (result == Messages.RESTART_VOTE_CAST) send(sender, result, "{vote}", choice);
        else send(sender, result);
    }

    void finishVote() {
        long yes;
        long no;
        TaskHandle oldVoteTask;
        synchronized (stateLock) {
            if (votes == null) return;
            yes = votes.values().stream().filter(Boolean::booleanValue).count();
            no = votes.size() - yes;
            votes = null;
            oldVoteTask = voteTask;
            voteTask = TaskHandle.NONE;
        }
        oldVoteTask.cancel();
        broadcast(yes > no ? Messages.RESTART_VOTE_PASSED : Messages.RESTART_VOTE_FAILED,
                "{yes}", Long.toString(yes), "{no}", Long.toString(no));
        Plan current = plan;
        if (yes > no && (current == null || secondsLeft(current) > 120)) {
            schedule(new Plan(Instant.now().plusSeconds(120), false, false));
        }
    }

    private void closeVote() {
        TaskHandle oldVoteTask;
        synchronized (stateLock) {
            votes = null;
            oldVoteTask = voteTask;
            voteTask = TaskHandle.NONE;
        }
        oldVoteTask.cancel();
    }

    private void cancel() {
        TaskHandle oldTask;
        synchronized (stateLock) {
            plan = null;
            oldTask = task;
            task = TaskHandle.NONE;
        }
        oldTask.cancel();
    }

    private void status(CommandSender sender) {
        Plan active = plan;
        if (active == null) send(sender, Messages.RESTART_NONE);
        else send(sender, Messages.RESTART_TIME, "{time}", format(secondsLeft(active)));
    }

    public String placeholder(String key) {
        Plan active = plan;
        return switch (key) {
            case "restart_time" -> active == null ? "" : format(secondsLeft(active));
            case "restart_seconds" -> active == null ? "0" : Long.toString(secondsLeft(active));
            case "restart_scheduled" -> Boolean.toString(active != null);
            default -> "";
        };
    }

    private static long secondsLeft(Plan active) {
        return Math.max(0, (Duration.between(Instant.now(), active.deadline()).toMillis() + 999) / 1000);
    }

    private static String format(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds / 3600 + "h " + seconds % 3600 / 60 + "m " + seconds % 60 + "s";
    }

    private boolean allowed(CommandSender sender, String permission) {
        return sender.hasPermission(PERMISSION + permission) || sender.hasPermission("allium.admin");
    }

    private void discord(String message) {
        RestartSettings currentSettings = settings;
        if (currentSettings != null && currentSettings.relay()
                && plugin.getServer().getPluginManager().isPluginEnabled("DiscordSRV")) {
            RestartDiscord.send(message, plugin.logger()::warning);
        }
    }

    private void send(CommandSender sender, Messages message, String... replacements) {
        UtilitySupport.send(plugin, sender, message, replacements);
    }

    private void broadcast(Messages message, String... replacements) {
        plugin.getMessageManager().broadcast(message, replacements);
    }

    @Override
    public void close() {
        cancel();
        closeVote();
        TaskHandle fallback;
        synchronized (stateLock) {
            fallback = shutdownFallback;
            shutdownFallback = TaskHandle.NONE;
        }
        fallback.cancel();
    }
}
