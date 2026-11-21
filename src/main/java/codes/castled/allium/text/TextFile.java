package codes.castled.allium.text;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.GcCommand;
import codes.castled.allium.customtext.CustomTextDocument;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.placeholder.AlliumPlaceholders;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One operator-written text file in {@code plugins/Allium} ({@code info.txt}, {@code custom.txt},
 * {@code motd.txt}, {@code rules.txt}, {@code broadcasts.txt}): parsed as a
 * {@link CustomTextDocument}, read again whenever it changes on disk, and sent line by line with
 * click and hover events ({@link InteractiveText}), backslash escapes ({@link InteractiveText#escape})
 * and placeholders filled for each recipient: Allium's {@code {player}}, {@code {server}} and the
 * rest, Essentials' keywords ({@code {balance}}, {@code {playerlist}}, {@code {tps}}, ...) in any
 * case, and PlaceholderAPI's when it is installed.
 *
 * <p>As in Essentials, a player reads {@code <name>_<player>.txt} (say {@code info_steve.txt}) when
 * it exists, else {@code <name>_<group>.txt} for their primary permission group, else the shared
 * file ({@link #fileNameFor}).
 */
public final class TextFile {

    /** {@code {keyword}}, or {@code {playerlist:<group>[:<fallback>]}}. */
    private static final Pattern KEYWORD = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)(?::([^{}:]*))?(?::([^{}]*))?}");
    private static final long UNIQUE_REFRESH_MILLIS = 60_000L;

    private record Loaded(CustomTextDocument document, long modified, long size) {
    }

    private final PluginStart plugin;
    private final String fileName;
    @Nullable
    private final String legacyName;
    private final Map<String, Loaded> loaded = new HashMap<>();
    private boolean renameFailed;
    private long uniqueCountedAt = Long.MIN_VALUE;
    private int uniqueCount;

    public TextFile(PluginStart plugin, String fileName) {
        this(plugin, fileName, null);
    }

    /** {@code legacyName}: the file's old name, moved to {@code fileName} on first start. */
    public TextFile(PluginStart plugin, String fileName, @Nullable String legacyName) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.legacyName = legacyName;
    }

    public String fileName() {
        return fileName;
    }

    /**
     * Writes the bundled example when the server has no file yet. A file under the old name is
     * moved to the new one instead.
     */
    public synchronized void installDefault() {
        File file = file(fileName);
        if (file.exists()) {
            return;
        }
        if (legacyName != null && file(legacyName).isFile()) {
            if (renameFailed) {
                return;
            }
            try {
                Files.move(file(legacyName).toPath(), file.toPath());
                plugin.logger().info("Renamed " + legacyName + " to " + fileName + ", Essentials' name for it.");
            } catch (IOException | RuntimeException failed) {
                renameFailed = true;
                plugin.logger().warning("Could not rename " + legacyName + " to " + fileName + " (" + failed.getMessage()
                        + "); rename it by hand.");
            }
            return;
        }
        try {
            plugin.saveResource(fileName, false);
        } catch (IllegalArgumentException missingResource) {
            plugin.logger().warning("The bundled " + fileName + " is missing from the jar.");
        }
    }

    /**
     * The file {@code sender} reads: {@code <name>_<player>.txt} when it exists, else
     * {@code <name>_<group>.txt} for their primary permission group (LuckPerms or Vault), else the
     * shared file. The console reads the shared file.
     */
    public String fileNameFor(CommandSender sender) {
        if (sender instanceof Player player) {
            String own = variant(player.getName());
            if (own != null && file(own).isFile()) {
                return own;
            }
            String group = variant(PlayerMeta.of(plugin, player).group());
            if (group != null && file(group).isFile()) {
                return group;
            }
        }
        return fileName;
    }

    /** {@code info_<key>.txt}: the key in lower case, anything but letters, digits, {@code _} and {@code -} as {@code _}. */
    @Nullable
    String variant(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String safe = key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (safe.length() > 64) {
            safe = safe.substring(0, 64);
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName + "_" + safe : fileName.substring(0, dot) + "_" + safe + fileName.substring(dot);
    }

    /** The shared file, read again when its size or modification time changes; null when it cannot be read. */
    @Nullable
    public CustomTextDocument document() {
        return document(fileName);
    }

    /** The file {@code sender} reads ({@link #fileNameFor}); null when it cannot be read. */
    @Nullable
    public CustomTextDocument document(CommandSender sender) {
        return document(fileNameFor(sender));
    }

    @Nullable
    private synchronized CustomTextDocument document(String name) {
        File file = file(name);
        if (name.equals(fileName) && !file.isFile()) {
            installDefault();
        }
        Loaded cached = loaded.get(name);
        if (!file.isFile()) {
            loaded.remove(name);
            return null;
        }
        long modified = file.lastModified();
        long size = file.length();
        if (cached != null && modified == cached.modified() && size == cached.size()) {
            return cached.document();
        }
        try {
            CustomTextDocument document = CustomTextDocument.parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            loaded.put(name, new Loaded(document, modified, size));
            return document;
        } catch (IOException | RuntimeException failed) {
            plugin.logger().warning("Could not read " + name + ": " + failed.getMessage());
            return cached == null ? null : cached.document();
        }
    }

    private File file(String name) {
        return new File(plugin.getDataFolder(), name);
    }

    /** Sends {@code lines} to one recipient, with placeholders filled for them when {@code placeholders}. */
    public void send(CommandSender sender, List<String> lines, boolean placeholders) {
        for (String raw : lines) {
            String escaped = InteractiveText.escape(raw);
            String line = placeholders ? fill(sender, escaped) : escaped;
            if (sender instanceof Player player && player.spigot() != null) {
                // An empty line still takes a row, as in the file.
                player.spigot().sendMessage(line.isEmpty()
                        ? new net.md_5.bungee.api.chat.BaseComponent[]{new net.md_5.bungee.api.chat.TextComponent(" ")}
                        : InteractiveText.components(line));
            } else {
                FormattedText formatted = InteractiveText.formatted(line);
                if (formatted.isEmpty()) {
                    sender.sendMessage("");
                } else {
                    TextSender.send(sender, formatted);
                }
            }
        }
    }

    /**
     * PlaceholderAPI's placeholders, then the keywords. Unknown keywords stay as written. Values
     * lose {@code <} and {@code >}, so a display name cannot open a click tag.
     */
    String fill(CommandSender sender, String line) {
        Player player = sender instanceof Player p ? p : null;
        String out = line;
        if (player != null && plugin.getServer().getPluginManager() != null
                && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") && out.indexOf('%') >= 0) {
            try {
                out = Papi.apply(player, out);
            } catch (LinkageError | RuntimeException unavailable) {
                // keep the text without PlaceholderAPI values
            }
        }
        if (out.indexOf('{') < 0) {
            return out;
        }
        Matcher m = KEYWORD.matcher(out);
        StringBuilder sb = new StringBuilder();
        PlayerMeta.Value[] meta = new PlayerMeta.Value[1];
        while (m.find()) {
            String keyword = m.group(1).toLowerCase(Locale.ROOT);
            String value;
            if (keyword.equals("playerlist")) {
                value = playerList(sender, m.group(2), m.group(3));
            } else if (m.group(2) != null) {
                value = null;
            } else {
                value = keyword(sender, player, keyword, meta);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? m.group() : value.replace("<", "").replace(">", "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** A keyword's value for {@code sender}; null when it is no keyword. */
    @Nullable
    private String keyword(CommandSender sender, @Nullable Player player, String keyword, PlayerMeta.Value[] meta) {
        var server = plugin.getServer();
        switch (keyword) {
            case "player", "username" -> {
                return sender.getName();
            }
            case "displayname" -> {
                return player == null ? sender.getName() : player.getDisplayName();
            }
            case "nickname" -> {
                String nickname = placeholder(player, "nickname");
                return nickname.isEmpty() && player != null ? player.getDisplayName() : nickname.isEmpty() ? sender.getName() : nickname;
            }
            case "prefix", "suffix", "group" -> {
                if (player == null) {
                    return "";
                }
                if (meta[0] == null) {
                    meta[0] = PlayerMeta.of(plugin, player);
                }
                return keyword.equals("prefix") ? meta[0].prefix() : keyword.equals("suffix") ? meta[0].suffix() : meta[0].group();
            }
            case "balance" -> {
                return placeholder(player, "balance_display");
            }
            case "mails" -> {
                return placeholder(player, "mail_unread");
            }
            case "playtime" -> {
                return placeholder(player, "playtime");
            }
            case "world", "worldname" -> {
                return player == null || player.getWorld() == null ? "" : player.getWorld().getName();
            }
            case "coords" -> {
                if (player == null) {
                    return "";
                }
                Location at = player.getLocation();
                return at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ();
            }
            case "worldtime12" -> {
                return placeholder(player, "world_time");
            }
            case "worldtime24" -> {
                return placeholder(player, "world_time_24");
            }
            case "worlddate" -> {
                return placeholder(player, "world_date");
            }
            case "online" -> {
                return Long.toString(server.getOnlinePlayers().stream().filter(other -> PlayerNameVisibility.canSee(sender, other)).count());
            }
            case "max", "maxplayers" -> {
                return Integer.toString(server.getMaxPlayers());
            }
            case "unique" -> {
                return Integer.toString(unique());
            }
            case "worlds" -> {
                return server.getWorlds().stream().map(World::getName).collect(Collectors.joining(", "));
            }
            case "time" -> {
                return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
            }
            case "date" -> {
                return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date());
            }
            case "tps" -> {
                return GcCommand.tps();
            }
            case "uptime" -> {
                return GcCommand.formatDuration(ManagementFactory.getRuntimeMXBean().getUptime());
            }
            case "ip", "address" -> {
                InetSocketAddress address = player == null ? null : player.getAddress();
                if (address == null) {
                    return "";
                }
                // The address the connection came from; no DNS lookup on the server thread.
                return keyword.equals("ip") && address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
            }
            case "plugins" -> {
                Plugin[] plugins = server.getPluginManager() == null ? new Plugin[0] : server.getPluginManager().getPlugins();
                return Arrays.stream(plugins).filter(Plugin::isEnabled).map(Plugin::getName)
                        .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
            }
            case "version" -> {
                return server.getVersion();
            }
            case "server" -> {
                return server.getName();
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The players online {@code sender} can see, by display name without its colours; with a group,
     * only that primary group's, and {@code fallback} when there are none.
     */
    private String playerList(CommandSender sender, @Nullable String group, @Nullable String fallback) {
        String wanted = group == null ? "" : group.trim();
        String list = plugin.getServer().getOnlinePlayers().stream()
                .filter(other -> PlayerNameVisibility.canSee(sender, other))
                .filter(other -> wanted.isEmpty() || wanted.equalsIgnoreCase(PlayerMeta.of(plugin, other).group()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(other -> TextFormatter.stripFormatting(other.getDisplayName()))
                .collect(Collectors.joining(", "));
        return list.isEmpty() && fallback != null ? fallback : list;
    }

    private String placeholder(@Nullable Player player, String key) {
        AlliumPlaceholders placeholders = plugin.getPlaceholders();
        if (player == null || placeholders == null) {
            return "";
        }
        String value = placeholders.request(player, key);
        return value == null ? "" : value;
    }

    /** Players who have ever joined, counted at most once a minute. */
    private int unique() {
        long now = System.currentTimeMillis();
        if (uniqueCountedAt == Long.MIN_VALUE || now - uniqueCountedAt > UNIQUE_REFRESH_MILLIS) {
            var offline = plugin.getServer().getOfflinePlayers();
            uniqueCount = offline == null ? 0 : offline.length;
            uniqueCountedAt = now;
        }
        return uniqueCount;
    }

    private static final class Papi {
        static String apply(Player player, String text) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        }
    }
}
