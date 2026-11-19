package codes.castled.allium.broadcast;

import codes.castled.allium.PluginStart;
import codes.castled.allium.customtext.CustomTextDocument;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.text.InteractiveText;
import codes.castled.allium.text.TextFile;
import codes.castled.allium.text.TextSender;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

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
    private Settings settings;
    private BroadcastRotation rotation;
    private TaskHandle task = TaskHandle.NONE;

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
        settings = Settings.read(plugin.getConfig(), plugin.logger()::warning);
        rotation = new BroadcastRotation(settings.order(), ThreadLocalRandom.current());
        if (settings.enabled()) {
            long period = settings.intervalSeconds() * 1000L;
            task = plugin.getScheduler().repeatOnServer(() -> {
                turn();
                return true;
            }, period, period);
        } else {
            task = TaskHandle.NONE;
        }
    }

    public synchronized void stop() {
        task.cancel();
        task = TaskHandle.NONE;
    }

    public Settings settings() {
        return settings;
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
    void turn() {
        long visible = plugin.getServer().getOnlinePlayers().stream().filter(p -> !PlayerNameVisibility.isVanished(p)).count();
        if (visible < settings.minimumPlayers()) {
            return;
        }
        next().ifPresent(this::send);
    }

    /** The next broadcast in the rotation, advancing it. */
    public Optional<String> next() {
        return rotation.next(broadcasts());
    }

    /** Sends one broadcast now; false when the file has no broadcast of that name. */
    public boolean send(String name) {
        CustomTextDocument text = file.document();
        List<String> lines = text == null ? null : text.section(name).orElse(null);
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (player.hasPermission(PERMISSION_RECEIVE)) {
                file.send(player, lines, true);
            }
        }
        file.send(plugin.getServer().getConsoleSender(), lines, true);
        return true;
    }

    /** {@code /broadcast <message>}: the {@code chat.broadcast} line to everyone, with click and hover tags. */
    public void announce(CommandSender sender, String message) {
        String line = plugin.getMessageManager().resolve(Messages.BROADCAST_FORMAT, "{message}", message, "{sender}", sender.getName());
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (player.spigot() != null) {
                player.spigot().sendMessage(InteractiveText.components(line));
            } else {
                TextSender.send(player, InteractiveText.formatted(line));
            }
            plugin.getMessageManager().playSoundIfPresent(player, Messages.BROADCAST_FORMAT);
        }
        // The console gets it as a log line that names who sent it.
        plugin.logger().info("Broadcast by " + sender.getName() + ": " + InteractiveText.formatted(line).toPlainText());
    }
}
