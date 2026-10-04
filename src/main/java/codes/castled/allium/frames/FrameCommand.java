package codes.castled.allium.frames;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /frame}: which lore frame a player gets, and building the frames.
 *
 * <pre>
 *   /frame                       your frame, and why
 *   /frame list                  every frame, and which you have
 *   /frame set &lt;player&gt; &lt;frame&gt;  override a player's frame
 *   /frame reset &lt;player&gt;        back to permissions and the fallback
 *   /frame build                 cut the sprite frames into the pack
 *   /frame reload                re-read frames.yml and the built frames
 * </pre>
 */
public final class FrameCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String ADMIN = "allium.frame.admin";

    private final Plugin plugin;

    public FrameCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    private static void msg(CommandSender to, String text) {
        to.sendMessage(MM.deserialize("<gradient:#d4a04a:#8a5a2a>[Frames]</gradient> " + text));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        FrameService frames = FrameService.get();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.isEmpty()) {
            if (!(sender instanceof Player player)) {
                msg(sender, "<gray>Usage: /frame <list|set|reset|build|reload></gray>");
                return true;
            }
            String override = frames.override(player.getUniqueId());
            msg(sender, "<gray>Your frame is <white>" + frames.resolve(player) + "</white>"
                + (override != null && frames.exists(override) ? " <dark_gray>(set for you)</dark_gray>" : "") + ".");
            return true;
        }
        if (sub.equals("list")) {
            List<String> parts = new ArrayList<>();
            for (String name : frames.names()) {
                FramesConfig.Entry e = frames.config().frames().get(name);
                boolean has = e != null && sender.hasPermission(e.permission());
                parts.add((has ? "<green>" : "<gray>") + name + (has ? "</green>" : "</gray>"));
            }
            msg(sender, "<gray>Frames: " + String.join("<dark_gray>, </dark_gray>", parts)
                + "<gray>. Fallback: <white>" + frames.config().fallback() + "</white>.");
            return true;
        }
        if (!sender.hasPermission(ADMIN)) {
            msg(sender, "<red>You do not have permission for that.</red>");
            return true;
        }
        switch (sub) {
            case "set" -> set(sender, args, frames);
            case "reset" -> reset(sender, args, frames);
            case "build" -> build(sender, frames);
            case "reload" -> reload(sender);
            default -> msg(sender, "<gray>Usage: /frame <list|set|reset|build|reload></gray>");
        }
        return true;
    }

    private void set(CommandSender sender, String[] args, FrameService frames) {
        if (args.length < 3) {
            msg(sender, "<gray>Usage: /frame set <player> <frame></gray>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!frames.exists(args[2])) {
            msg(sender, "<red>No frame called '" + args[2] + "'. See /frame list.</red>");
            return;
        }
        try {
            frames.setOverride(target.getUniqueId(), args[2]);
            msg(sender, "<green>" + args[1] + " now has the <white>" + FramesConfig.name(args[2])
                + "</white> frame.</green>");
        } catch (Exception e) {
            msg(sender, "<red>Could not save the override: " + e.getMessage() + "</red>");
        }
    }

    private void reset(CommandSender sender, String[] args, FrameService frames) {
        if (args.length < 2) {
            msg(sender, "<gray>Usage: /frame reset <player></gray>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        try {
            frames.setOverride(target.getUniqueId(), null);
            msg(sender, "<green>" + args[1] + " is back to their permissions and the fallback.</green>");
        } catch (Exception e) {
            msg(sender, "<red>Could not save: " + e.getMessage() + "</red>");
        }
    }

    private void build(CommandSender sender, FrameService frames) {
        File pack = new File(frames.config().pack());
        if (!pack.isDirectory()) {
            msg(sender, "<red>No pack folder at " + pack.getPath() + "; set pack: in frames.yml.</red>");
            return;
        }
        msg(sender, "<gray>Building " + frames.config().spriteFrames().size() + " frame(s)...</gray>");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var result = FrameBuilder.build(frames.config(), pack,
                    new File(plugin.getDataFolder(), FrameService.MANIFEST));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    List<String> problems = new ArrayList<>(result.problems());
                    problems.addAll(FrameService.load(plugin));
                    msg(sender, "<green>Built " + result.frames() + " frame(s) from "
                        + result.glyphs() + " glyph(s).</green> <gray>Run /nexo reload pack to send them.</gray>");
                    problems.forEach(p -> msg(sender, "<yellow>" + p + "</yellow>"));
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                    msg(sender, "<red>Build failed: " + e.getMessage() + "</red>"));
            }
        });
    }

    private void reload(CommandSender sender) {
        List<String> problems = FrameService.load(plugin);
        msg(sender, "<green>Reloaded frames.yml.</green>");
        problems.forEach(p -> msg(sender, "<yellow>" + p + "</yellow>"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("list");
            if (sender.hasPermission(ADMIN)) options.addAll(List.of("set", "reset", "build", "reload"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("reset"))) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            options.addAll(FrameService.get().names());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
