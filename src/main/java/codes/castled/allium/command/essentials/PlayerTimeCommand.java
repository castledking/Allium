package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.BukkitCommandRenderer;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.essentials.WorldTimeParser;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * {@code /ptime [list|reset|@<time>|<time>] [player|*]} ({@code /playertime}).
 *
 * <p>Controls a player's client-side clock independently of the world clock. Prefixing a time with
 * {@code @} freezes the player's clock at that time; omitting {@code @} keeps the clock moving at
 * the chosen offset from world time.
 */
public final class PlayerTimeCommand implements SpecCommandHandler {

    private static final List<String> FIRST_SUGGESTIONS = List.of(
            "list", "reset",
            "day", "noon", "sunset", "night", "midnight", "sunrise", "dawn",
            "@day", "@noon", "@sunset", "@night", "@midnight", "@sunrise", "@dawn"
    );

    private final PluginStart plugin;

    public PlayerTimeCommand(PluginStart plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.PTIME;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();

        if (args.length > 2) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }

        if (args.length == 0) {
            if (sender instanceof Player player) {
                sendPlayerStatus(sender, player);
            } else {
                listAllPlayers(sender);
            }
            return;
        }

        String first = args[0].trim();
        String lower = first.toLowerCase(Locale.ROOT);

        if (lower.equals("list") || lower.equals("get") || lower.equals("show") || lower.equals("display")) {
            handleQuery(context, lower, args.length == 2 ? args[1] : null);
            return;
        }

        if (lower.equals("reset")) {
            handleReset(context, args.length == 2 ? args[1] : null);
            return;
        }

        boolean fixed = first.startsWith("@");
        String timeToken = fixed ? first.substring(1) : first;
        OptionalLong parsed = WorldTimeParser.parseTimeTicks(timeToken);
        if (parsed.isEmpty()) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }

        handleSet(context, parsed.getAsLong(), fixed, args.length == 2 ? args[1] : null);
    }

    private void handleQuery(BukkitCommandContext context, String verb, String targetArg) {
        CommandSender sender = context.sender();
        if (targetArg == null) {
            if (!(sender instanceof Player player)) {
                listAllPlayers(sender);
                return;
            }
            if (verb.equals("list") && sender.hasPermission(spec().othersPermission())) {
                listAllPlayers(sender);
            } else {
                sendPlayerStatus(sender, player);
            }
            return;
        }

        if ("*".equals(targetArg)) {
            if (!requireOthersPermission(sender)) {
                return;
            }
            listAllPlayers(sender);
            return;
        }

        Player target = resolveTarget(sender, targetArg);
        if (target == null) {
            return;
        }
        sendPlayerStatus(sender, target);
    }

    private void handleReset(BukkitCommandContext context, String targetArg) {
        CommandSender sender = context.sender();
        if (targetArg == null) {
            if (!(sender instanceof Player player)) {
                context.sendInvalidArgs("/ptime reset <player|*>");
                return;
            }
            player.resetPlayerTime();
            UtilitySupport.send(plugin, player, Messages.UTILITY_PTIME_RESET_SELF);
            UtilitySupport.audit(plugin, sender, "ptime", "target=" + player.getName() + " mode=reset");
            return;
        }

        if ("*".equals(targetArg)) {
            if (!requireOthersPermission(sender)) {
                return;
            }
            List<Player> targets = visibleOnlinePlayers(sender);
            if (targets.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.UTILITY_PTIME_LIST_EMPTY);
                return;
            }
            for (Player target : targets) {
                target.resetPlayerTime();
                if (!target.equals(sender)) {
                    UtilitySupport.send(plugin, target, Messages.UTILITY_PTIME_RESET_SELF);
                }
            }
            UtilitySupport.send(plugin, sender, Messages.UTILITY_PTIME_RESET_ALL, "{count}", Integer.toString(targets.size()));
            UtilitySupport.audit(plugin, sender, "ptime", "target=* count=" + targets.size() + " mode=reset");
            return;
        }

        Player target = resolveTarget(sender, targetArg);
        if (target == null) {
            return;
        }
        target.resetPlayerTime();
        if (target.equals(sender)) {
            UtilitySupport.send(plugin, target, Messages.UTILITY_PTIME_RESET_SELF);
        } else {
            UtilitySupport.send(plugin, sender, Messages.UTILITY_PTIME_RESET_OTHER, "{player}", target.getName());
            UtilitySupport.send(plugin, target, Messages.UTILITY_PTIME_RESET_SELF);
        }
        UtilitySupport.audit(plugin, sender, "ptime", "target=" + target.getName() + " mode=reset");
    }

    private void handleSet(BukkitCommandContext context, long ticks, boolean fixed, String targetArg) {
        CommandSender sender = context.sender();
        String formatted = WorldTimeParser.formatTime(ticks);

        if (targetArg == null) {
            if (!(sender instanceof Player player)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            applyTime(player, ticks, fixed);
            UtilitySupport.send(plugin, player,
                    fixed ? Messages.UTILITY_PTIME_SET_FIXED_SELF : Messages.UTILITY_PTIME_SET_SELF,
                    "{time}", formatted);
            UtilitySupport.audit(plugin, sender, "ptime", "target=" + player.getName() + " ticks=" + ticks + " fixed=" + fixed);
            return;
        }

        if ("*".equals(targetArg)) {
            if (!requireOthersPermission(sender)) {
                return;
            }
            List<Player> targets = visibleOnlinePlayers(sender);
            if (targets.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.UTILITY_PTIME_LIST_EMPTY);
                return;
            }
            for (Player target : targets) {
                applyTime(target, ticks, fixed);
                if (!target.equals(sender)) {
                    UtilitySupport.send(plugin, target,
                            fixed ? Messages.UTILITY_PTIME_SET_FIXED_SELF : Messages.UTILITY_PTIME_SET_SELF,
                            "{time}", formatted);
                }
            }
            UtilitySupport.send(plugin, sender,
                    fixed ? Messages.UTILITY_PTIME_SET_FIXED_ALL : Messages.UTILITY_PTIME_SET_ALL,
                    "{time}", formatted,
                    "{count}", Integer.toString(targets.size()));
            UtilitySupport.audit(plugin, sender, "ptime", "target=* count=" + targets.size() + " ticks=" + ticks + " fixed=" + fixed);
            return;
        }

        Player target = resolveTarget(sender, targetArg);
        if (target == null) {
            return;
        }
        applyTime(target, ticks, fixed);
        if (target.equals(sender)) {
            UtilitySupport.send(plugin, target,
                    fixed ? Messages.UTILITY_PTIME_SET_FIXED_SELF : Messages.UTILITY_PTIME_SET_SELF,
                    "{time}", formatted);
        } else {
            UtilitySupport.send(plugin, sender,
                    fixed ? Messages.UTILITY_PTIME_SET_FIXED_OTHER : Messages.UTILITY_PTIME_SET_OTHER,
                    "{player}", target.getName(),
                    "{time}", formatted);
            UtilitySupport.send(plugin, target,
                    fixed ? Messages.UTILITY_PTIME_SET_FIXED_SELF : Messages.UTILITY_PTIME_SET_SELF,
                    "{time}", formatted);
        }
        UtilitySupport.audit(plugin, sender, "ptime", "target=" + target.getName() + " ticks=" + ticks + " fixed=" + fixed);
    }

    private static void applyTime(Player target, long ticks, boolean fixed) {
        if (fixed) {
            target.setPlayerTime(ticks, false);
            return;
        }
        long worldTime = target.getWorld() == null ? 0L : target.getWorld().getTime();
        long offset = ((ticks - (worldTime % WorldTimeParser.DAY_TICKS)) % WorldTimeParser.DAY_TICKS + WorldTimeParser.DAY_TICKS)
                % WorldTimeParser.DAY_TICKS;
        target.setPlayerTime(offset, true);
    }

    private void listAllPlayers(CommandSender sender) {
        List<Player> targets = visibleOnlinePlayers(sender);
        if (targets.isEmpty()) {
            UtilitySupport.send(plugin, sender, Messages.UTILITY_PTIME_LIST_EMPTY);
            return;
        }
        for (Player target : targets) {
            sendPlayerStatus(sender, target);
        }
    }

    private void sendPlayerStatus(CommandSender viewer, Player target) {
        String formatted = WorldTimeParser.formatTime(target.getPlayerTime());
        boolean relative = target.isPlayerTimeRelative();
        long offset = target.getPlayerTimeOffset();
        Messages message;
        if (!relative) {
            message = Messages.UTILITY_PTIME_STATUS_FIXED;
        } else if (offset == 0L) {
            message = Messages.UTILITY_PTIME_STATUS_NORMAL;
        } else {
            message = Messages.UTILITY_PTIME_STATUS_RELATIVE;
        }
        UtilitySupport.send(plugin, viewer, message, "{player}", target.getName(), "{time}", formatted);
    }

    private boolean requireOthersPermission(CommandSender sender) {
        String others = spec().othersPermission();
        if (others == null || sender.hasPermission(others)) {
            return true;
        }
        UtilitySupport.send(plugin, sender, Messages.NO_PERMISSION, "{cmd}", spec().canonicalName());
        return false;
    }

    private Player resolveTarget(CommandSender sender, String targetArg) {
        boolean targetingSelf = sender instanceof Player p && p.getName() != null && p.getName().equalsIgnoreCase(targetArg);
        if (!targetingSelf && !requireOthersPermission(sender)) {
            return null;
        }
        Player target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, sender, targetArg);
        if (target != null && !target.equals(sender) && !requireOthersPermission(sender)) {
            return null;
        }
        return target;
    }

    private List<Player> visibleOnlinePlayers(CommandSender viewer) {
        if (plugin.getServer() == null || plugin.getServer().getOnlinePlayers() == null) {
            return List.of();
        }
        List<Player> out = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online != null && PlayerNameVisibility.canSeeTarget(viewer, online)) {
                out.add(online);
            }
        }
        out.sort(Comparator.comparing(p -> p.getName() == null ? "" : p.getName().toLowerCase(Locale.ROOT)));
        return out;
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(spec().basePermission())) {
            return List.of();
        }
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return FIRST_SUGGESTIONS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && sender.hasPermission(spec().othersPermission())) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("*".startsWith(prefix)) {
                out.add("*");
            }
            for (String name : BukkitCommandRenderer.collectVisibleOnlinePlayers(plugin.getServer(), sender)) {
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(name);
                }
            }
            return out;
        }
        return List.of();
    }
}
