package codes.castled.allium.legacy;

import codes.castled.allium.broadcast.BroadcastRotation;
import codes.castled.allium.customtext.CustomTextDocument;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class LegacyTextModule implements Listener {
    private static final String BROADCASTS = "broadcasts.txt";
    private static final String RECEIVE = "allium.broadcasts.receive";
    private static final Pattern FORMAT_TAG = Pattern.compile(
            "<(?:/?(?:click|hover|gradient|rainbow)(?::(?:\"[^\"]*\"|'[^']*'|[^\"'<>])*+)?|#[0-9a-fA-F]{6})>",
            Pattern.CASE_INSENSITIVE);

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyModerationModule moderation;
    private BroadcastRotation rotation;
    private BukkitTask timer;
    private long interval;
    private int minimum;
    private boolean scheduled;

    LegacyTextModule(JavaPlugin plugin, LegacyConfigManager config, LegacyModerationModule moderation) {
        this.plugin = plugin;
        this.config = config;
        this.moderation = moderation;
    }

    void reload() {
        stop();
        FileConfiguration values = config.raw();
        interval = Math.max(10L, Math.min(86_400L, values.getLong("broadcasts.interval-seconds", 300L)));
        minimum = Math.max(0, values.getInt("broadcasts.minimum-players", 1));
        scheduled = values.getBoolean("broadcasts.enabled", false);
        BroadcastRotation.Order order = BroadcastRotation.Order.parse(values.getString("broadcasts.order"))
                .orElse(BroadcastRotation.Order.SEQUENTIAL);
        rotation = new BroadcastRotation(order, ThreadLocalRandom.current());
        if (scheduled) {
            timer = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                long visible = plugin.getServer().getOnlinePlayers().stream()
                        .filter(p -> !moderation.isVanished(p.getUniqueId())).count();
                if (visible >= minimum) {
                    CustomTextDocument document = read(BROADCASTS, null);
                    rotation.next(broadcastNames(document)).ifPresent(name -> announce(document, name));
                }
            }, interval * 20L, interval * 20L);
        }
    }

    void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!config.raw().getBoolean("motd.show-on-join", true)) return;
        Player joined = event.getPlayer();
        UUID playerId = joined.getUniqueId();
        long delay = Math.max(0L, Math.min(200L, config.raw().getLong("motd.join-delay-ticks", 10L)));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != joined || !player.isOnline() || !player.hasPermission("allium.motd")
                    || !config.raw().getBoolean("motd.show-on-join", true)) return;
            CustomTextDocument document = read("motd.txt", player);
            if (document != null) send(player, CustomTextDocument.page(document.index(), 1).lines());
        }, delay);
    }

    boolean handles(String command) {
        return file(command) != null || "inform".equals(command) || "broadcasts".equals(command);
    }

    boolean execute(CommandSender sender, String command, String[] args) {
        if ("broadcasts".equals(command)) return broadcasts(sender, args);
        if ("inform".equals(command)) {
            if (args.length < 1 || args.length > 3) return false;
            Player player = visiblePlayer(sender, args[0]);
            if (player == null) {
                sender.sendMessage("That player is not online.");
                return true;
            }
            return show(player, sender, "info", Arrays.copyOfRange(args, 1, args.length));
        }
        return show(sender, sender, command, args);
    }

    private boolean show(CommandSender viewer, CommandSender sender, String command, String[] args) {
        if (args.length > 2 || (args.length == 2 && !isPage(args[1]))) return false;
        String filename = file(command);
        CustomTextDocument document = read(filename, viewer);
        if (document == null) {
            sender.sendMessage(filename + " could not be read. Check the console.");
            return true;
        }
        String section = args.length == 0 ? null : args[0];
        int page = args.length == 2 ? Integer.parseInt(args[1]) : 1;
        if (args.length == 1 && isPage(section) && !document.section(section).isPresent()) {
            page = Integer.parseInt(section);
            section = null;
        }
        List<String> lines = section == null ? document.index() : document.section(section).orElse(null);
        if (lines == null || (section != null && lines.isEmpty())) {
            sender.sendMessage("No text was found for '" + section + "'.");
            return true;
        }
        if (lines.isEmpty()) {
            List<String> names = primaryNames(document);
            viewer.sendMessage(names.isEmpty() ? filename + " has nothing to show yet."
                    : "Sections: " + String.join(", ", names));
            return true;
        }
        CustomTextDocument.Page shown = CustomTextDocument.page(lines, page);
        send(viewer, shown.lines());
        if (shown.pages() > 1) {
            String path = "/" + command + " " + (section == null ? "" : document.primaryName(section).get() + " ");
            LegacyCompat.sendHelpFooter(viewer, "Prev", shown.page() > 1 ? path + (shown.page() - 1) : null,
                    "Page " + shown.page() + "/" + shown.pages(), "Next",
                    shown.page() < shown.pages() ? path + (shown.page() + 1) : null);
        }
        return true;
    }

    private boolean broadcasts(CommandSender sender, String[] args) {
        CustomTextDocument document = read(BROADCASTS, null);
        List<String> names = broadcastNames(document);
        if (args.length == 0) {
            sender.sendMessage("Timed broadcasts " + (scheduled ? "on" : "off") + " (" + interval + "s, "
                    + rotation.order().name().toLowerCase(Locale.ROOT) + ", minimum players " + minimum + ").");
            sender.sendMessage("Broadcasts: " + (names.isEmpty() ? "-" : String.join(", ", names)));
            return true;
        }
        String name;
        if (args.length == 1 && "next".equalsIgnoreCase(args[0])) {
            name = rotation.next(names).orElse(null);
        } else if (args.length == 2 && "send".equalsIgnoreCase(args[0])) {
            name = document == null ? null : document.primaryName(args[1]).orElse(null);
        } else {
            return false;
        }
        if (name == null || !announce(document, name)) {
            sender.sendMessage("No matching broadcast was found.");
        } else {
            sender.sendMessage("Sent broadcast '" + name + "'.");
        }
        return true;
    }

    private boolean announce(CustomTextDocument document, String name) {
        List<String> lines = document.section(name).orElse(Collections.emptyList());
        if (lines.isEmpty()) return false;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission(RECEIVE)) send(player, lines);
        }
        send(plugin.getServer().getConsoleSender(), lines);
        return true;
    }

    List<String> complete(CommandSender sender, String command, String[] args) {
        if ("broadcasts".equals(command)) {
            if (args.length == 1) return filter(Arrays.asList("send", "next"), args[0]);
            if (args.length == 2 && "send".equalsIgnoreCase(args[0])) {
                CustomTextDocument document = read(BROADCASTS, null);
                return document == null ? Collections.emptyList() : filter(document.sectionNames(), args[1]);
            }
        } else if ("inform".equals(command)) {
            if (args.length == 1) {
                List<String> names = new ArrayList<>();
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (canSee(sender, player)) names.add(player.getName());
                }
                return filter(names, args[0]);
            }
            if (args.length == 2) {
                Player player = visiblePlayer(sender, args[0]);
                if (player != null) return completeSection(player, "info.txt", args[1]);
            }
        } else if (args.length == 1) {
            return completeSection(sender, file(command), args[0]);
        }
        return Collections.emptyList();
    }

    private List<String> completeSection(CommandSender viewer, String filename, String prefix) {
        CustomTextDocument document = read(filename, viewer);
        return document == null ? Collections.emptyList() : filter(document.sectionNames(), prefix);
    }

    private Player visiblePlayer(CommandSender sender, String name) {
        Player player = plugin.getServer().getPlayerExact(name);
        return player != null && canSee(sender, player) ? player : null;
    }

    private boolean canSee(CommandSender sender, Player player) {
        return !(sender instanceof Player) || ((Player) sender).canSee(player);
    }

    private CustomTextDocument read(String filename, CommandSender viewer) {
        File file = new File(plugin.getDataFolder(), filename);
        if ("custom.txt".equals(filename) && !file.exists()) {
            File old = new File(file.getParentFile(), "customtext.txt");
            if (old.isFile()) file = old;
        }
        if (viewer instanceof Player) {
            String playerName = viewer.getName().toLowerCase(Locale.ROOT);
            if (playerName.matches("[a-z0-9_.-]{1,32}")) {
                File personal = new File(file.getParentFile(), filename.replace(".txt", "_" + playerName + ".txt"));
                if (personal.isFile()) file = personal;
            }
        }
        try {
            return CustomTextDocument.parse(Files.readAllLines(file.toPath()));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private void send(CommandSender receiver, List<String> lines) {
        String displayName = receiver instanceof Player ? ((Player) receiver).getDisplayName() : receiver.getName();
        String online = Integer.toString(plugin.getServer().getOnlinePlayers().size());
        String max = Integer.toString(plugin.getServer().getMaxPlayers());
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{\"") && trimmed.endsWith("}") && LegacyCompat.sendJson(receiver, trimmed)) continue;
            String text = FORMAT_TAG.matcher(line).replaceAll("")
                    .replace("{server}", plugin.getServer().getName())
                    .replace("{online}", online).replace("{max}", max)
                    .replace("{displayname}", displayName).replace("{player}", receiver.getName());
            receiver.sendMessage(LegacyCompat.color(text));
        }
    }

    private static List<String> broadcastNames(CustomTextDocument document) {
        if (document == null) return Collections.emptyList();
        return primaryNames(document).stream().filter(name -> !document.section(name).get().isEmpty())
                .collect(Collectors.toList());
    }

    private static List<String> primaryNames(CustomTextDocument document) {
        return document.sectionNames().stream().filter(name -> document.primaryName(name).get().equals(name))
                .collect(Collectors.toList());
    }

    private static List<String> filter(List<String> names, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower)).limit(64)
                .collect(Collectors.toList());
    }

    private static boolean isPage(String raw) {
        return raw.matches("[0-9]{1,6}");
    }

    private static String file(String command) {
        switch (command) {
            case "info": return "info.txt";
            case "rules": return "rules.txt";
            case "motd": return "motd.txt";
            case "customtext": return "custom.txt";
            default: return null;
        }
    }
}
