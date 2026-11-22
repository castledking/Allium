package codes.castled.allium.restart;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandArgSpec;
import codes.castled.allium.command.spec.CommandMessageKeys;
import codes.castled.allium.command.spec.CommandNodeSpec;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.SuggestionSpec;
import codes.castled.allium.config.YamlFiles;
import codes.castled.allium.message.Messages;
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
import java.util.regex.Pattern;

public final class AutoRestart implements SpecCommandHandler, AutoCloseable {
    private static final String PERMISSION = "allium.autorestart.";
    private static final List<String> VOTES = List.of("start", "yes", "no");
    private static final String USAGE = "/ar <help|time|now|stop|reload|vote>";
    private static final CommandSpec SPEC = new CommandSpec("autorestart", CommandSpec.DEFAULT_NAMESPACE, "core",
            List.of("ar"), "Schedule or manage server restarts", USAGE, USAGE, null, null,
            List.of(), List.of(node("help", "help"), node("time", "time", "status"),
            node("now", "now", List.of(CommandArgSpec.optional("time", null,
                    SuggestionSpec.literals(List.of("30s", "5m", "--dry-run"), null)),
                    CommandArgSpec.optional("mode", null, SuggestionSpec.literals(List.of("--dry-run"), null))), "delay"),
            node("stop", "stop"), node("reload", "reload"),
            new CommandNodeSpec("vote", List.of(), PERMISSION + "vote.yes", "/ar vote [start|yes|no]",
                    List.of(CommandArgSpec.optional("choice", null, SuggestionSpec.literals(VOTES, null))), List.of())),
            CommandMessageKeys.ofOutcomes(Map.of()));
    private static final CommandSpec VOTE_SPEC = new CommandSpec("voterestart", CommandSpec.DEFAULT_NAMESPACE,
            "core", List.of(), "Vote for a server restart", "/voterestart [yes|no|start]", "/voterestart [yes|no|start]",
            PERMISSION + "vote.yes", null, List.of(CommandArgSpec.optional("choice", null,
            SuggestionSpec.literals(VOTES, null))), List.of(), CommandMessageKeys.ofOutcomes(Map.of()));
    private static final Pattern DELAY = Pattern.compile("(\\d+)([smhd]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK_DELAY = Pattern.compile("(\\d{1,2}):([0-5]\\d)");

    private record Plan(Instant deadline, boolean preCommands, boolean dryRun) {}

    private final PluginStart plugin;
    private RestartSettings settings;
    private volatile Plan plan;
    private TaskHandle task = TaskHandle.NONE;
    private TaskHandle voteTask = TaskHandle.NONE;
    private long lastSeconds;
    private boolean prepared;
    private boolean discordWarned;
    private boolean stopping;
    private Map<UUID, Boolean> votes;

    public AutoRestart(PluginStart plugin) {
        this.plugin = plugin;
    }

    private static CommandNodeSpec node(String name, String permission, String... aliases) {
        return node(name, permission, List.of(), aliases);
    }

    private static CommandNodeSpec node(String name, String permission, List<CommandArgSpec> args, String... aliases) {
        return new CommandNodeSpec(name, List.of(aliases), PERMISSION + permission, "/ar " + name, args, List.of());
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    public SpecCommandHandler voteCommand() {
        return new SpecCommandHandler() {
            @Override public CommandSpec spec() { return VOTE_SPEC; }
            @Override public void execute(BukkitCommandContext context) {
                String[] args = context.args();
                if (args.length == 0 && !allowed(context.sender(), "vote")) {
                    if (votes == null) status(context.sender());
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
        cancel();
        if (stopping) return;
        plan = next;
        prepared = false;
        discordWarned = false;
        lastSeconds = secondsLeft(next);
        task = plugin.getScheduler().repeatOnServer(() -> tick(next), 50, 250);
    }

    private boolean tick(Plan active) {
        if (plan != active) return false;
        long seconds = secondsLeft(active);
        if (active.preCommands() && !prepared && seconds <= settings.commandDelay()) {
            prepared = true;
            for (String command : settings.preCommands()) {
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
            cancel();
            if (active.dryRun()) {
                broadcast(Messages.RESTART_DRY_COMPLETE);
                scheduleDaily();
            } else stopServer();
            return false;
        }
        long previous = lastSeconds;
        var countdown = settings.countdown().stream().filter(n -> n > 0 && n >= seconds && n < previous)
                .min(Integer::compareTo);
        if (countdown.isPresent()) {
            broadcast(Messages.RESTART_COUNTDOWN, "{time}", Long.toString(seconds), "{plural}", seconds == 1 ? "" : "s");
        }
        lastSeconds = seconds;
        if (!active.dryRun() && !discordWarned && settings.discordWarning() > 0 && seconds <= settings.discordWarning()) {
            discordWarned = true;
            discord("Server will restart in " + format(seconds) + ".");
        }
        return true;
    }

    private void stopServer() {
        stopping = true;
        closeVote();
        broadcast(Messages.RESTART_NOW);
        discord("Server is restarting now.");
        try {
            if (settings.save()) dispatch("minecraft:save-all flush");
        } catch (RuntimeException error) {
            plugin.logger().warning("Pre-restart save command failed. The server will save during shutdown.");
        }
        if (settings.finalMessage()) broadcast(Messages.RESTART_STOPPED);
        String kick = TextFormatter.parse(plugin.getMessageManager().resolve(Messages.RESTART_KICK)).toLegacy();
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            try {
                player.kickPlayer(kick);
            } catch (RuntimeException error) {
                plugin.logger().warning("Could not send the restart kick to " + player.getName() + ".");
            }
        }
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
            if (!allowed(sender, "vote")) send(sender, Messages.NO_PERMISSION);
            else if (settings == null) send(sender, Messages.RESTART_CONFIG_ERROR);
            else if (votes != null) send(sender, Messages.RESTART_VOTE_ACTIVE);
            else {
                votes = new HashMap<>();
                broadcast(Messages.RESTART_VOTE_PROMPT);
                voteTask = plugin.getScheduler().runOnServerLater(this::finishVote, 60_000);
            }
            return;
        }
        if (!(sender instanceof Player player)) send(sender, Messages.PLAYER_ONLY);
        else if (!allowed(sender, "vote.yes")) send(sender, Messages.NO_PERMISSION);
        else if (votes == null) send(sender, Messages.RESTART_VOTE_NONE);
        else if (votes.putIfAbsent(player.getUniqueId(), choice.equals("yes")) != null) {
            send(sender, Messages.RESTART_VOTE_DUPLICATE);
        } else send(sender, Messages.RESTART_VOTE_CAST, "{vote}", choice);
    }

    private void finishVote() {
        if (votes == null) return;
        long yes = votes.values().stream().filter(Boolean::booleanValue).count();
        long no = votes.size() - yes;
        closeVote();
        broadcast(yes > no ? Messages.RESTART_VOTE_PASSED : Messages.RESTART_VOTE_FAILED,
                "{yes}", Long.toString(yes), "{no}", Long.toString(no));
        if (yes > no && (plan == null || secondsLeft(plan) > 120)) {
            schedule(new Plan(Instant.now().plusSeconds(120), false, false));
        }
    }

    private void closeVote() {
        votes = null;
        voteTask.cancel();
        voteTask = TaskHandle.NONE;
    }

    private void cancel() {
        plan = null;
        task.cancel();
        task = TaskHandle.NONE;
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
        if (settings.relay() && plugin.getServer().getPluginManager().isPluginEnabled("DiscordSRV")) {
            RestartDiscord.send(message, plugin.logger()::warning);
        }
    }

    private void send(CommandSender sender, Messages message, String... replacements) {
        plugin.getMessageManager().send(sender, message, replacements);
    }

    private void broadcast(Messages message, String... replacements) {
        plugin.getMessageManager().broadcast(message, replacements);
    }

    @Override
    public void close() {
        cancel();
        closeVote();
    }
}
