package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class GamemodeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> GAMEMODE_COMPLETIONS = Arrays.asList("survival", "creative", "adventure", "spectator");

    private final PluginStart plugin;

    public GamemodeCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("allium.gamemode")) {
            plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "gamemode");
            return true;
        }

        if (args.length == 0) {
            plugin.getMessageManager().send(sender, Messages.INVALID_ARGS, "{usage}", "/gamemode <mode> [player]");
            return true;
        }

        GameMode mode = parseGamemode(args[0]);
        if (mode == null) {
            plugin.getMessageManager().send(sender, Messages.INVALID_ARGS, "{usage}", "/gamemode <survival|creative|adventure|spectator> [player]");
            return true;
        }

        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("allium.gamemode.others")) {
                plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "gamemode for others");
                return true;
            }
            target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[1]);
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                plugin.getMessageManager().send(sender, Messages.INVALID_ARGS, "{usage}", "/gamemode <mode> <player>");
                return true;
            }
            target = (Player) sender;
        }

        target.setGameMode(mode);
        if (target != sender) {
            plugin.getMessageManager().send(sender, Messages.GAMEMODE_OTHER, "{player}", target.getName(), "{mode}", mode.name().toLowerCase());
        }
        plugin.getMessageManager().send(target, Messages.GAMEMODE_SELF, "{mode}", mode.name().toLowerCase());
        return true;
    }

    private GameMode parseGamemode(String input) {
        switch (input.toLowerCase()) {
            case "0": case "s": case "survival": return GameMode.SURVIVAL;
            case "1": case "c": case "creative": return GameMode.CREATIVE;
            case "2": case "a": case "adventure": return GameMode.ADVENTURE;
            case "3": case "sp": case "spectator": return GameMode.SPECTATOR;
            default: return null;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return GAMEMODE_COMPLETIONS.stream()
                .filter(g -> g.startsWith(prefix))
                .collect(Collectors.toList());
        }
        if (args.length == 2) {
            return null;
        }
        return List.of();
    }
}
