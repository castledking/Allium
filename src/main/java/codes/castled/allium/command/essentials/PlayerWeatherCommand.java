package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.BukkitCommandRenderer;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.WeatherType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code /pweather [list|reset|sun|storm] [player|*]} ({@code /playerweather}).
 *
 * <p>Controls a player's client-side weather independently of the world's weather.
 */
public final class PlayerWeatherCommand implements SpecCommandHandler {

    private static final List<String> FIRST_SUGGESTIONS = List.of("list", "reset", "sun", "clear", "storm", "rain");

    private final PluginStart plugin;

    public PlayerWeatherCommand(PluginStart plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.PWEATHER;
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

        String lower = args[0].trim().toLowerCase(Locale.ROOT);
        if (lower.equals("list") || lower.equals("get") || lower.equals("show") || lower.equals("display")) {
            handleQuery(lower, sender, args.length == 2 ? args[1] : null);
            return;
        }
        if (lower.equals("reset")) {
            handleReset(context, args.length == 2 ? args[1] : null);
            return;
        }

        WeatherType weather = switch (lower) {
            case "sun", "clear" -> WeatherType.CLEAR;
            case "storm", "rain", "thunder", "downfall" -> WeatherType.DOWNFALL;
            default -> null;
        };
        if (weather == null) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }

        handleSet(context, weather, args.length == 2 ? args[1] : null);
    }

    private void handleQuery(String verb, CommandSender sender, String targetArg) {
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
                context.sendInvalidArgs("/pweather reset <player|*>");
                return;
            }
            player.resetPlayerWeather();
            UtilitySupport.send(plugin, player, Messages.UTILITY_PWEATHER_RESET_SELF);
            UtilitySupport.audit(plugin, sender, "pweather", "target=" + player.getName() + " weather=reset");
            return;
        }

        if ("*".equals(targetArg)) {
            if (!requireOthersPermission(sender)) {
                return;
            }
            List<Player> targets = visibleOnlinePlayers(sender);
            if (targets.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.UTILITY_PWEATHER_LIST_EMPTY);
                return;
            }
            for (Player target : targets) {
                target.resetPlayerWeather();
                if (!target.equals(sender)) {
                    UtilitySupport.send(plugin, target, Messages.UTILITY_PWEATHER_RESET_SELF);
                }
            }
            UtilitySupport.send(plugin, sender, Messages.UTILITY_PWEATHER_RESET_ALL, "{count}", Integer.toString(targets.size()));
            UtilitySupport.audit(plugin, sender, "pweather", "target=* count=" + targets.size() + " weather=reset");
            return;
        }

        Player target = resolveTarget(sender, targetArg);
        if (target == null) {
            return;
        }
        target.resetPlayerWeather();
        if (target.equals(sender)) {
            UtilitySupport.send(plugin, target, Messages.UTILITY_PWEATHER_RESET_SELF);
        } else {
            UtilitySupport.send(plugin, sender, Messages.UTILITY_PWEATHER_RESET_OTHER, "{player}", target.getName());
            UtilitySupport.send(plugin, target, Messages.UTILITY_PWEATHER_RESET_SELF);
        }
        UtilitySupport.audit(plugin, sender, "pweather", "target=" + target.getName() + " weather=reset");
    }

    private void handleSet(BukkitCommandContext context, WeatherType weather, String targetArg) {
        CommandSender sender = context.sender();
        boolean clear = weather == WeatherType.CLEAR;
        Messages selfMsg = clear ? Messages.UTILITY_PWEATHER_SET_CLEAR_SELF : Messages.UTILITY_PWEATHER_SET_STORM_SELF;
        Messages otherMsg = clear ? Messages.UTILITY_PWEATHER_SET_CLEAR_OTHER : Messages.UTILITY_PWEATHER_SET_STORM_OTHER;
        Messages allMsg = clear ? Messages.UTILITY_PWEATHER_SET_CLEAR_ALL : Messages.UTILITY_PWEATHER_SET_STORM_ALL;

        if (targetArg == null) {
            if (!(sender instanceof Player player)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            player.setPlayerWeather(weather);
            UtilitySupport.send(plugin, player, selfMsg);
            UtilitySupport.audit(plugin, sender, "pweather", "target=" + player.getName() + " weather=" + weather.name().toLowerCase(Locale.ROOT));
            return;
        }

        if ("*".equals(targetArg)) {
            if (!requireOthersPermission(sender)) {
                return;
            }
            List<Player> targets = visibleOnlinePlayers(sender);
            if (targets.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.UTILITY_PWEATHER_LIST_EMPTY);
                return;
            }
            for (Player target : targets) {
                target.setPlayerWeather(weather);
                if (!target.equals(sender)) {
                    UtilitySupport.send(plugin, target, selfMsg);
                }
            }
            UtilitySupport.send(plugin, sender, allMsg, "{count}", Integer.toString(targets.size()));
            UtilitySupport.audit(plugin, sender, "pweather", "target=* count=" + targets.size() + " weather=" + weather.name().toLowerCase(Locale.ROOT));
            return;
        }

        Player target = resolveTarget(sender, targetArg);
        if (target == null) {
            return;
        }
        target.setPlayerWeather(weather);
        if (target.equals(sender)) {
            UtilitySupport.send(plugin, target, selfMsg);
        } else {
            UtilitySupport.send(plugin, sender, otherMsg, "{player}", target.getName());
            UtilitySupport.send(plugin, target, selfMsg);
        }
        UtilitySupport.audit(plugin, sender, "pweather", "target=" + target.getName() + " weather=" + weather.name().toLowerCase(Locale.ROOT));
    }

    private void listAllPlayers(CommandSender sender) {
        List<Player> targets = visibleOnlinePlayers(sender);
        if (targets.isEmpty()) {
            UtilitySupport.send(plugin, sender, Messages.UTILITY_PWEATHER_LIST_EMPTY);
            return;
        }
        for (Player target : targets) {
            sendPlayerStatus(sender, target);
        }
    }

    private void sendPlayerStatus(CommandSender viewer, Player target) {
        WeatherType wt = target.getPlayerWeather();
        Messages message;
        if (wt == null) {
            message = Messages.UTILITY_PWEATHER_STATUS_NORMAL;
        } else if (wt == WeatherType.CLEAR) {
            message = Messages.UTILITY_PWEATHER_STATUS_CLEAR;
        } else {
            message = Messages.UTILITY_PWEATHER_STATUS_STORM;
        }
        UtilitySupport.send(plugin, viewer, message, "{player}", target.getName());
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
