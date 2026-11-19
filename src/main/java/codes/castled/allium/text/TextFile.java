package codes.castled.allium.text;

import codes.castled.allium.PluginStart;
import codes.castled.allium.customtext.CustomTextDocument;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One operator-written text file in {@code plugins/Allium} ({@code customtext.txt},
 * {@code motd.txt}, {@code rules.txt}, {@code broadcasts.txt}): parsed as a
 * {@link CustomTextDocument}, read again whenever it changes on disk, and sent line by line with
 * click and hover events ({@link InteractiveText}) and the {@code {player}}, {@code {displayname}},
 * {@code {world}}, {@code {online}}, {@code {max}} and {@code {server}} placeholders (plus
 * PlaceholderAPI's when it is installed), filled for each recipient.
 */
public final class TextFile {

    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)\\{(player|displayname|world|online|max|maxplayers|server)}");

    private final PluginStart plugin;
    private final String fileName;
    private CustomTextDocument document;
    private long loadedModified = Long.MIN_VALUE;
    private long loadedSize = -1;

    public TextFile(PluginStart plugin, String fileName) {
        this.plugin = plugin;
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }

    /** Writes the bundled example when the server has no file yet. */
    public void installDefault() {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException missingResource) {
                plugin.logger().warning("The bundled " + fileName + " is missing from the jar.");
            }
        }
    }

    /** The parsed file, read again when its size or modification time changes; null when it cannot be read. */
    @Nullable
    public synchronized CustomTextDocument document() {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.isFile()) {
            installDefault();
        }
        if (!file.isFile()) {
            return null;
        }
        long modified = file.lastModified();
        long size = file.length();
        if (document != null && modified == loadedModified && size == loadedSize) {
            return document;
        }
        try {
            document = CustomTextDocument.parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            loadedModified = modified;
            loadedSize = size;
            return document;
        } catch (IOException | RuntimeException failed) {
            plugin.logger().warning("Could not read " + fileName + ": " + failed.getMessage());
            return document;
        }
    }

    /** Sends {@code lines} to one recipient, with placeholders filled for them when {@code placeholders}. */
    public void send(CommandSender sender, List<String> lines, boolean placeholders) {
        for (String raw : lines) {
            String line = placeholders ? fill(sender, raw) : raw;
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

    /** Placeholders; values a player controls lose {@code <} and {@code >} so they cannot open click tags. */
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
        Matcher m = PLACEHOLDER.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "player" -> sender.getName();
                case "displayname" -> player == null ? sender.getName() : player.getDisplayName();
                case "world" -> player == null || player.getWorld() == null ? "" : player.getWorld().getName();
                case "online" -> Long.toString(plugin.getServer().getOnlinePlayers().stream()
                        .filter(other -> PlayerNameVisibility.canSee(sender, other)).count());
                case "max", "maxplayers" -> Integer.toString(plugin.getServer().getMaxPlayers());
                default -> plugin.getServer().getName();
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value.replace("<", "").replace(">", "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final class Papi {
        static String apply(Player player, String text) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        }
    }
}
