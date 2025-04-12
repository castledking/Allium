package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.utils.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class Gamemode implements CommandExecutor {

    private final Map<String, GameMode> gamemodeAliases = new HashMap<>();
    private final Map<String, GameMode> commandAliases = new HashMap<>();
    private final PluginStart plugin;
    private final Lang lang;

    public Gamemode(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();

        // Initialize gamemode aliases
        gamemodeAliases.put("0", GameMode.SURVIVAL);
        gamemodeAliases.put("1", GameMode.CREATIVE);
        gamemodeAliases.put("2", GameMode.ADVENTURE);
        gamemodeAliases.put("3", GameMode.SPECTATOR);
        gamemodeAliases.put("c", GameMode.CREATIVE);
        gamemodeAliases.put("s", GameMode.SURVIVAL);
        gamemodeAliases.put("a", GameMode.ADVENTURE);
        gamemodeAliases.put("sp", GameMode.SPECTATOR);
        gamemodeAliases.put("survival", GameMode.SURVIVAL);
        gamemodeAliases.put("creative", GameMode.CREATIVE);
        gamemodeAliases.put("adventure", GameMode.ADVENTURE);
        gamemodeAliases.put("spectator", GameMode.SPECTATOR);

        // Initialize command aliases for direct gamemode switching
        commandAliases.put("gmc", GameMode.CREATIVE);
        commandAliases.put("gms", GameMode.SURVIVAL);
        commandAliases.put("gma", GameMode.ADVENTURE);
        commandAliases.put("gmsp", GameMode.SPECTATOR);
        commandAliases.put("creative", GameMode.CREATIVE);
        commandAliases.put("survival", GameMode.SURVIVAL);
        commandAliases.put("adventure", GameMode.ADVENTURE);
        commandAliases.put("spectator", GameMode.SPECTATOR);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        // Check if using a direct gamemode command (gmc, gms, gma, gmsp)
        GameMode directMode = commandAliases.get(label.toLowerCase());
        if (directMode != null) {
            return handleDirectGamemodeCommand(sender, directMode, args, label);
        }

        // Standard gamemode command handling
        if (args.length == 0 || args.length > 2) {
            sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", label)
                    .replace("{args}", "<mode> [player]"));
            return true;
        }

        GameMode mode = gamemodeAliases.get(args[0].toLowerCase());
        if (mode == null) {
            Text.sendErrorMessage(sender, "gamemode.invalid", lang, "{mode}", args[0]);
            return true;
        }

        // Check if sender has permission for this gamemode
        if (!hasGameModePermission(sender, mode)) {
            Text.sendErrorMessage(sender, "no-permission", lang);
            return true;
        }

        Player target;

        // If command has only mode, it must be player executing it.
        if (args.length == 1) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "<mode> <player>"));
                return true;
            }
            target = (Player) sender;
        } else {
            // Check if sender has permission to change other players' gamemode
            if (!sender.hasPermission("core.gamemode.others")) {
                Text.sendErrorMessage(sender, "no-permission", lang);
                return true;
            }

            // Attempt to find target player
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{player}", args[1]);
                return true;
            }
        }

        setGamemode(target, mode, sender);
        return true;
    }

    private boolean handleDirectGamemodeCommand(CommandSender sender, GameMode mode, String[] args, String label) {
        // Check if sender has permission for this gamemode
        if (!hasGameModePermission(sender, mode)) {
            Text.sendErrorMessage(sender, "no-permission", lang);
            return true;
        }

        Player target;

        // No arguments - set gamemode for sender
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "<player>"));
                return true;
            }
            target = (Player) sender;
        }
        // One argument - set gamemode for specified player
        else if (args.length == 1) {
            // Check if sender has permission to change other players' gamemode
            if (!sender.hasPermission("core.gamemode.others")) {
                Text.sendErrorMessage(sender, "no-permission", lang);
                return true;
            }

            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{player}", args[0]);
                return true;
            }
        }
        // Too many arguments
        else {
            sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", label)
                    .replace("{args}", "[player]"));
            return true;
        }

        setGamemode(target, mode, sender);
        return true;
    }

    /**
     * Checks if a sender has permission to use a specific gamemode
     *
     * @param sender The command sender
     * @param mode The gamemode to check
     * @return true if the sender has permission, false otherwise
     */
    private boolean hasGameModePermission(CommandSender sender, GameMode mode) {
        // Check for specific gamemode permission
        String permissionSuffix = mode.name().toLowerCase();

        // Player must have the specific gamemode permission
        return sender.hasPermission("core.gamemode." + permissionSuffix);
    }

    private void setGamemode(Player player, GameMode mode, CommandSender sender) {
        player.setGameMode(mode);

        if (sender.equals(player)) {
            sender.sendMessage(lang.get("gamemode.switch").replace("{gamemode}", mode.toString()));
        } else {
            sender.sendMessage(lang.get("gamemode.switch-other").replace("{name}", player.getName()).replace("{gamemode}", mode.toString()));
            player.sendMessage(lang.get("gamemode.switch").replace("{gamemode}", mode.toString()));
        }
    }
}
