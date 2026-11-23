package codes.castled.allium.broadcast;

import codes.castled.allium.PluginStart;
import codes.castled.allium.customtext.CustomTextDocument;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.text.InteractiveText;
import codes.castled.allium.text.TextFile;
import codes.castled.allium.text.TextSender;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Timed broadcasts: every {@code broadcasts.interval-seconds}, the next {@code #section} of
 * {@code broadcasts.txt} ({@link BroadcastRotation}) is sent to every online player with
 * {@code allium.broadcasts.receive} (default true) and to the console, placeholders filled per
 * recipient. Idle unless {@code broadcasts.enabled}; a turn is skipped while fewer than
 * {@code broadcasts.minimum-players} unvanished players are online. Also sends staff's
 * {@code /broadcast} messages.
 */
public final class BroadcastService {

    public static final String FILE = "broadcasts.txt";
    public static final String PERMISSION_RECEIVE = "allium.broadcasts.receive";
    public static final long MIN_INTERVAL_SECONDS = 10;
    public static final long MAX_INTERVAL_SECONDS = 86_400;

    /** The {@code broadcasts} section of config.yml, bounded and with defaults. */
    public record Settings(boolean enabled, long intervalSeconds, BroadcastRotation.Order order, int minimumPlayers) {
        static Settings read(FileConfiguration config, java.util.function.Consumer<String> warn) {
            long interval = config.getLong("broadcasts.interval-seconds", 300);
            if (interval < MIN_INTERVAL_SECONDS || interval > MAX_INTERVAL_SECONDS) {
                warn.accept("broadcasts.interval-seconds must be " + MIN_INTERVAL_SECONDS + "-" + MAX_INTERVAL_SECONDS + "; using "
                        + Math.max(MIN_INTERVAL_SECONDS, Math.min(MAX_INTERVAL_SECONDS, interval)));
                interval = Math.max(MIN_INTERVAL_SECONDS, Math.min(MAX_INTERVAL_SECONDS, interval));
            }
            String rawOrder = config.getString("broadcasts.order", "sequential");
            BroadcastRotation.Order order = BroadcastRotation.Order.parse(rawOrder).orElseGet(() -> {
                warn.accept("broadcasts.order must be sequential or random, not '" + rawOrder + "'; using sequential");
                return BroadcastRotation.Order.SEQUENTIAL;
            });
            int minimum = Math.max(0, config.getInt("broadcasts.minimum-players", 1));
            return new Settings(config.getBoolean("broadcasts.enabled", false), interval, order, minimum);
        }
    }

    private final PluginStart plugin;
    private final TextFile file;
    private volatile Schedule schedule;
    /** Only the recipient that reaches zero queues the global rotation step. */
    private volatile AtomicInteger pendingMinimum;
    private volatile boolean stopped;
    private TaskHandle task = TaskHandle.NONE;

    private record Schedule(Settings settings, BroadcastRotation rotation) {}

    public BroadcastService(PluginStart plugin) {
        this.plugin = plugin;
        this.file = new TextFile(plugin, FILE);
    }

    public void start() {
        file.installDefault();
        reload();
    }

    /** Re-reads the settings and restarts the timer (on {@code /allium reload}). */
    public synchronized void reload() {
        task.cancel();
        Settings settings = Settings.read(plugin.getConfig(), plugin.logger()::warning);
        var next = new Schedule(settings, new BroadcastRotation(settings.order(), ThreadLocalRandom.current()));
        schedule = next;
        pendingMinimum = null;
        stopped = false;
        if (settings.enabled()) {
            long period = settings.intervalSeconds() * 1000L;
            task = plugin.getScheduler().repeatOnServer(() -> {
                turn(next);
                return true;
            }, period, period);
        } else {
            task = TaskHandle.NONE;
        }
    }

    public synchronized void stop() {
        stopped = true;
        pendingMinimum = null;
        task.cancel();
        task = TaskHandle.NONE;
    }

    public Settings settings() {
        return schedule.settings();
    }

    public TextFile file() {
        return file;
    }

    /** The broadcasts in file order, by their first name. */
    public List<String> broadcasts() {
        CustomTextDocument text = file.document();
        if (text == null) {
            return List.of();
        }
        return text.sectionNames().stream().filter(name -> text.primaryName(name).map(name::equals).orElse(false)).toList();
    }

    /** One timed turn: the next broadcast, unless too few players are online. */
    private void turn(Schedule current) {
        var players = List.copyOf(plugin.getServer().getOnlinePlayers());
        int minimum = current.settings().minimumPlayers();
        if (!BukkitServerThread.isFolia()) {
            if (players.stream().filter(p -> !PlayerNameVisibility.isVanished(p)).count() >= minimum) {
                finishTurn(current, null);
            }
        } else if (minimum == 0) {
            finishTurn(current, null);
        } else if (players.size() >= minimum) {
            var round = new AtomicInteger(minimum);
            synchronized (this) {
                if (stopped || schedule != current) return;
                pendingMinimum = round;
            }
            for (Player player : players) {
                onPlayer(player, recipient -> {
                    if (pendingMinimum == round && !PlayerNameVisibility.isVanished(recipient)
                            && round.decrementAndGet() == 0) {
                        plugin.getScheduler().runOnServer(() -> finishTurn(current, round));
                    }
                });
            }
        } else {
            synchronized (this) {
                if (schedule == current) pendingMinimum = null;
            }
        }
    }

    private void finishTurn(Schedule current, AtomicInteger round) {
        Optional<String> name;
        synchronized (this) {
            if (stopped || schedule != current || pendingMinimum != round) return;
            pendingMinimum = null;
            name = current.rotation().next(broadcasts());
        }
        name.ifPresent(value -> send(value, current));
    }

    /** The next broadcast in the rotation, advancing it. */
    public Optional<String> next() {
        return schedule.rotation().next(broadcasts());
    }

    /** Sends one broadcast now; false when the file has no broadcast of that name. */
    public boolean send(String name) {
        return send(name, null);
    }

    private boolean send(String name, Schedule scheduled) {
        if (!canSend(scheduled)) return false;
        CustomTextDocument text = file.document();
        List<String> lines = text == null ? null : text.section(name).orElse(null);
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        var delivery = file.delivery(lines);
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            onPlayer(player, recipient ->
                    delivery.send(recipient, () -> canSend(scheduled) && recipient.hasPermission(PERMISSION_RECEIVE)));
        }
        var console = plugin.getServer().getConsoleSender();
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), console, () -> delivery.send(console, () -> canSend(scheduled)));
        return true;
    }

    private boolean canSend(Schedule scheduled) {
        return !stopped && (scheduled == null || schedule == scheduled);
    }

    private void onPlayer(Player player, Consumer<Player> action) {
        if (BukkitServerThread.isFolia()) {
            var owner = BukkitServerThread.entity(plugin, player);
            plugin.getScheduler().runOn(owner, () -> {
                if (player.isOnline() && !stopped) action.accept(player);
            }, () -> {}, 0);
        } else {
            action.accept(player);
        }
    }

    /** {@code /broadcast <message>}: the {@code chat.broadcast} line to everyone, with click and hover tags. */
    public void announce(CommandSender sender, String message) {
        announce(sender, message, null);
    }

    public void announce(CommandSender sender, String message, World world) {
        String line = plugin.getMessageManager().resolve(Messages.BROADCAST_FORMAT, "{message}", message, "{sender}", sender.getName());
        var players = world == null || BukkitServerThread.isFolia() ? plugin.getServer().getOnlinePlayers() : world.getPlayers();
        for (Player player : List.copyOf(players)) {
            onPlayer(player, recipient -> {
                if (world != null && BukkitServerThread.isFolia() && recipient.getWorld() != world) return;
                if (recipient.spigot() != null) recipient.spigot().sendMessage(InteractiveText.components(line));
                else TextSender.send(recipient, InteractiveText.formatted(line));
                plugin.getMessageManager().playSoundIfPresent(recipient, Messages.BROADCAST_FORMAT);
            });
        }
        // The console gets it as a log line that names who sent it.
        plugin.logger().info("Broadcast" + (world == null ? "" : " in " + world.getName()) + " by " + sender.getName() + ": " + InteractiveText.formatted(line).toPlainText());
    }
}
