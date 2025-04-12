package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.LangManager;
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
    private final PluginStart plugin;
    private final LangManager lang;

    public Gamemode(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager(); // Assuming you have a getter for LangManager

        // Initialize gamemode aliases (customize as needed)
        gamemodeAliases.put("0", GameMode.SURVIVAL);
        gamemodeAliases.put("1", GameMode.CREATIVE);
        gamemodeAliases.put("2", GameMode.ADVENTURE);
        gamemodeAliases.put("3", GameMode.SPECTATOR);
        gamemodeAliases.put("c", GameMode.CREATIVE);
        gamemodeAliases.put("s", GameMode.SURVIVAL);
        gamemodeAliases.put("a", GameMode.ADVENTURE);
        gamemodeAliases.put("sp", GameMode.SPECTATOR);
        gamemodeAliases.put("creative", GameMode.CREATIVE);
        gamemodeAliases.put("survival", GameMode.SURVIVAL);
        gamemodeAliases.put("adventure", GameMode.ADVENTURE);
        gamemodeAliases.put("spectator", GameMode.SPECTATOR);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player) && args.length == 0) {
            sender.sendMessage(lang.get("not-a-player")); // Example from LangManager
            return true;
        }

        Player target;

        if (args.length == 0) {
            target = (Player) sender;
            GameMode mode = gamemodeAliases.get(label.toLowerCase()); // Get gamemode from command alias
            if (mode == null) {
                sender.sendMessage(lang.get("commands.gamemode.invalid-gamemode").replace("{gamemode}", label));
                return true;
            }
            if(!sender.hasPermission("core.gamemode." + mode.name().toLowerCase())) {
                sender.sendMessage(lang.get("no-permission")); // Replace with your permission message
                return true;
            }
            setGamemode(target, mode, sender);

        } else if (args.length == 1) {
            if (sender.hasPermission("core.gamemode.others")) {
                target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage(lang.get("player-not-found").replace("%player%", args[0]));
                    return true;
                }
                GameMode mode = gamemodeAliases.get(label.toLowerCase()); // Get gamemode from command alias
                if (mode == null) {
                    sender.sendMessage(lang.get("commands.gamemode.invalid-gamemode"));
                    return true;
                }
                setGamemode(target, mode, sender);
            } else {
                sender.sendMessage(lang.get("no-permission")); // Replace with your permission message
            }

        } else {
            return false; // Show usage
        }

        return true;
    }

    private void setGamemode(Player player, GameMode mode, CommandSender sender) {
        player.setGameMode(mode);

        if (sender.equals(player)) {
            sender.sendMessage(lang.get("commands.gamemode.success").replace("{gamemode}", mode.toString()));
        } else {
            sender.sendMessage(lang.get("commands.gamemode.success-other").replace("{player}", player.getName()).replace("{gamemode}", mode.toString()));
            player.sendMessage(lang.get("commands.gamemode.success").replace("{gamemode}", mode.toString()).replace("{sender}", sender.getName()));
        }
    }
}