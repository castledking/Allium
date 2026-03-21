package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PvpCommand implements CommandExecutor, TabCompleter {

    private static final String PVP_ENABLED_KEY = "pvp_enabled";

    private final PluginStart plugin;
    private final Lang lang;

    public PvpCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    public static boolean isPvpEnabled(PluginStart plugin, Player player) {
        PersistentDataContainer dataContainer = player.getPersistentDataContainer();
        Integer value = dataContainer.get(getPvpEnabledKey(plugin), PersistentDataType.INTEGER);
        return value == null || value == 1;
    }

    public static void setPvpEnabledState(PluginStart plugin, Player player, boolean enabled) {
        player.getPersistentDataContainer().set(getPvpEnabledKey(plugin), PersistentDataType.INTEGER, enabled ? 1 : 0);
    }

    private static NamespacedKey getPvpEnabledKey(PluginStart plugin) {
        return new NamespacedKey(plugin, PVP_ENABLED_KEY);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("pvp")) {
            return false;
        }

        if (!(sender instanceof Player player) && args.length < 2) {
            sender.sendMessage(lang.get("command-usage")
                .replace("{cmd}", label)
                .replace("{args}", "<on|off> <player>"));
            return true;
        }

        if (!sender.hasPermission("allium.pvp")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", label)
                    .replace("{args}", "<on|off> <player>"));
                return true;
            }
            boolean newState = !isPvpEnabled(plugin, player);
            applyState(sender, player, newState, true);
            return true;
        }

        Boolean requestedState = parseState(args[0]);
        if (requestedState == null) {
            sender.sendMessage(lang.get("command-usage")
                .replace("{cmd}", label)
                .replace("{args}", "[on|off] [player]"));
            return true;
        }

        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", label)
                    .replace("{args}", "<on|off> <player>"));
                return true;
            }
            applyState(sender, player, requestedState, true);
            return true;
        }

        if (!sender.hasPermission("allium.pvp.other")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " on others");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
            return true;
        }

        applyState(sender, target, requestedState, sender == target);
        return true;
    }

    private void applyState(CommandSender sender, Player target, boolean enabled, boolean sameSender) {
        setPvpEnabledState(plugin, target, enabled);
        Bukkit.getAsyncScheduler().runNow(plugin, task ->
            plugin.getDatabase().setPvpEnabled(target.getUniqueId(), target.getName(), enabled));

        String stateText = enabled
            ? lang.get("styles.state.true") + "enabled"
            : lang.get("styles.state.false") + "disabled";

        if (sameSender) {
            sender.sendMessage(Text.colorize(lang.get("pvp.toggle-self").replace("{state}", stateText)));
            return;
        }

        sender.sendMessage(Text.colorize(lang.get("pvp.toggle-other")
            .replace("{state}", stateText)
            .replace("{player}", target.getName())));
        target.sendMessage(Text.colorize(lang.get("pvp.toggle-self").replace("{state}", stateText)));
    }

    private Boolean parseState(String input) {
        String normalized = input.toLowerCase(Locale.ENGLISH);
        return switch (normalized) {
            case "on" -> true;
            case "off" -> false;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("pvp")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if ("on".startsWith(args[0].toLowerCase(Locale.ENGLISH))) {
                options.add("on");
            }
            if ("off".startsWith(args[0].toLowerCase(Locale.ENGLISH))) {
                options.add("off");
            }
            return options;
        }

        if (args.length == 2 && sender.hasPermission("allium.pvp.other")) {
            String prefix = args[1].toLowerCase(Locale.ENGLISH);
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ENGLISH).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        }

        return List.of();
    }
}
