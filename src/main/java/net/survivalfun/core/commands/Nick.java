package net.survivalfun.core.commands;

import me.croabeast.prismatic.PrismaticAPI;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public class Nick implements CommandExecutor {
    private final PluginStart plugin;

    public Nick(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean isPlayer = sender instanceof Player;
        
        // Check basic nick permission (for using /nick at all)
        boolean hasNickPerm = isPlayer && ((Player) sender).hasPermission("allium.nick");
        boolean hasGuiPerm = isPlayer && ((Player) sender).hasPermission("allium.nick.gui");
        boolean hasOthersPerm = isPlayer && ((Player) sender).hasPermission("allium.nick.others");
        boolean hasAdminPerm = isPlayer && ((Player) sender).hasPermission("allium.admin");
        
        // Check for -p (pass-through) or -c (command mode) flags
        boolean passThrough = false;
        boolean forceCommand = false;
        int argIndex = 0;
        
        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-p")) {
                passThrough = true;
            } else if (args[i].equalsIgnoreCase("-c")) {
                forceCommand = true;
            } else {
                argIndex = i;
                break;
            }
        }
        
        // If -p or -c flag is used, require admin permission
        if ((passThrough || forceCommand) && !hasAdminPerm) {
            Text.sendErrorMessage(sender, "no-permission", plugin.getLangManager(), "use /{cmd}", "admin features.", true);
            return true;
        }
        
        // Adjust args to skip flags
        String[] actualArgs = new String[args.length - argIndex];
        System.arraycopy(args, argIndex, actualArgs, 0, actualArgs.length);
        
        // /nick --apply <nickname> [<playerName>] (from dialog; server runs command, so last arg is target player)
        if (actualArgs.length >= 1 && "--apply".equals(actualArgs[0])) {
            if (actualArgs.length < 2) return true;
            // Last arg is the target player name (appended by dialog when run as console)
            int end = actualArgs.length - 1;
            String targetName = actualArgs[end];
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null || !target.isOnline()) {
                if (sender instanceof Player) ((Player) sender).sendMessage("§cPlayer not found: " + targetName);
                return true;
            }
            String nickname = (end > 1)
                ? String.join(" ", java.util.Arrays.copyOfRange(actualArgs, 1, end)).trim()
                : actualArgs[1].trim();
            if (nickname.isEmpty()) return true;
            if (!target.hasPermission("allium.nick.gui")) return true;
            if (!plugin.getNicknameManager().isValidNickname(nickname)) {
                // Check if it's because the nickname matches a player name
                String stripped = PrismaticAPI.stripAll(nickname);
                if (plugin.getServer().getPlayerExact(stripped) != null || 
                    (plugin.getServer().getOfflinePlayer(stripped) != null && 
                     plugin.getServer().getOfflinePlayer(stripped).hasPlayedBefore())) {
                    target.sendMessage("§cThat nickname is already in use by a player.");
                } else {
                    target.sendMessage("§cInvalid nickname.");
                }
                return true;
            }
            if (plugin.getNicknameManager().isOnCooldown(target)) {
                target.sendMessage("§cCooldown. Wait " + plugin.getNicknameManager().getRemainingCooldown(target) + "s.");
                return true;
            }
            if (plugin.getNicknameManager().setNickname(target, nickname)) {
                target.sendMessage("§aNickname updated.");
            }
            return true;
        }
        
        // /nick with no args - show usage
        if (actualArgs.length == 0) {
            if (!isPlayer) {
                Text.sendErrorMessage(sender, "player-only-command", plugin.getLangManager(), "use /{cmd}", "execute this command.", true);
                return true;
            }
            Player player = (Player) sender;
            if (hasNickPerm || hasGuiPerm) {
                sender.sendMessage("§cUsage: /nick <name>");
            } else {
                Text.sendErrorMessage(player, "no-permission", plugin.getLangManager(), "use /{cmd}", "set your nickname.", true);
            }
            return true;
        }
        
        // /nick <name> - always run as command with permission checks (no dialog bypass)
        if (actualArgs.length == 1) {
            if (!isPlayer) {
                Text.sendErrorMessage(sender, "player-only-command", plugin.getLangManager(), "use /{cmd}", "execute this command.", true);
                return true;
            }
            Player player = (Player) sender;
            if (!hasNickPerm && !hasGuiPerm) {
                Text.sendErrorMessage(player, "no-permission", plugin.getLangManager(), "{cmd}", "nick", true);
                return true;
            }
            String firstArg = actualArgs[0];
            // Always check permissions when args are provided (no dialog bypass)
            if (!hasColorPermission(player, firstArg)) {
                Text.sendErrorMessage(player, "no-permission", plugin.getLangManager(), "use /{cmd}", "use those colors/formats.", true);
                return true;
            }
            return setNickname(player, player, firstArg);
        }
        
        // Pass-through mode (-p): directly set nickname without GUI
        if (passThrough) {
            if (actualArgs.length < 1) {
                sender.sendMessage("§cUsage: /nick <player> <nickname> -p");
                return true;
            }
            
            Player target;
            String nickname;
            
            if (actualArgs.length == 1) {
                // /nick <nickname> -p (edit own nickname)
                if (!isPlayer) {
                    Text.sendErrorMessage(sender, "player-only-command", plugin.getLangManager(), "use /{cmd}", "execute this command.", true);
                    return true;
                }
                target = (Player) sender;
                nickname = actualArgs[0];
            } else {
                // /nick <player> <nickname> -p (edit others)
                if (!hasOthersPerm && !hasAdminPerm) {
                    Text.sendErrorMessage(sender, "no-permission", plugin.getLangManager(), "edit /{cmd}", "other players' nicknames.", true);
                    return true;
                }
                target = Bukkit.getPlayerExact(actualArgs[0]);
                if (target == null) {
                    Text.sendErrorMessage(sender, "player-not-found", plugin.getLangManager(), "{name}", actualArgs[0]);
                    return true;
                }
                nickname = actualArgs[1];
            }
            
            return setNickname(sender, target, nickname);
        }
        
        String targetName = actualArgs[0];
        
        // /nick <player> <name> - editing others
        if (actualArgs.length >= 2) {
            if (!isPlayer) {
                // Console can edit others
                Player target = Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    Text.sendErrorMessage(sender, "player-not-found", plugin.getLangManager(), "{name}", targetName);
                    return true;
                }
                String nickname = actualArgs[1];
                return setNickname(sender, target, nickname);
            }
            
            Player player = (Player) sender;
            
            // Check permission to edit others
            if (!hasOthersPerm && !hasAdminPerm) {
                Text.sendErrorMessage(player, "no-permission", plugin.getLangManager(), "edit /{cmd}", "other players' nicknames.", true);
                return true;
            }
            
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                Text.sendErrorMessage(player, "player-not-found", plugin.getLangManager(), "{name}", targetName);
                return true;
            }
            
            // /nick <player> <name> - set directly without GUI
            String nickname = actualArgs[1];
            if (!hasColorPermission(player, nickname)) {
                Text.sendErrorMessage(player, "no-permission", plugin.getLangManager(), "use /{cmd}", "use those colors/formats.", true);
                return true;
            }
            return setNickname(player, target, nickname);
        }
        
        return true;
    }
    
    private boolean hasColorPermission(Player player, String nickname) {
        // Strip colors to check raw length
        String stripped = PrismaticAPI.stripAll(nickname);
        
        // Check for hex colors (#RRGGBB)
        if (nickname.contains("#")) {
            if (!player.hasPermission("allium.nick.color.hex")) {
                return false;
            }
        }
        
        // Check for MiniMessage patterns (<red>, <gradient:...>, <rainbow>, etc.)
        if (nickname.contains("<gradient") || nickname.contains("<rainbow")
                || nickname.contains("<red>") || nickname.contains("<blue>") || nickname.contains("<green>")
                || nickname.matches(".*<[a-zA-Z0-9_]+>.*")) {
            if (!player.hasPermission("allium.nick.minimessage")) {
                return false;
            }
        }
        
        // Check basic color codes
        String[] codes = {"4", "c", "6", "e", "a", "2", "b", "3", "1", "9", "5", "d", "0", "8", "7", "f"};
        for (String code : codes) {
            if (nickname.contains("&" + code) || nickname.contains("§" + code)) {
                if (!player.hasPermission("allium.nick.color." + getColorName(code))) {
                    return false;
                }
            }
        }
        
        // Check format codes
        String[] formats = {"l", "n", "o", "m", "k"};
        String[] formatNames = {"bold", "underline", "italic", "strikethrough", "obfuscated"};
        for (int i = 0; i < formats.length; i++) {
            if (nickname.contains("&" + formats[i]) || nickname.contains("§" + formats[i])) {
                if (!player.hasPermission("allium.nick.format." + formatNames[i])) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    private String getColorName(String code) {
        return switch (code) {
            case "0" -> "black";
            case "1" -> "dark_blue";
            case "2" -> "dark_green";
            case "3" -> "dark_aqua";
            case "4" -> "dark_red";
            case "5" -> "dark_purple";
            case "6" -> "gold";
            case "7" -> "gray";
            case "8" -> "dark_gray";
            case "9" -> "blue";
            case "a" -> "green";
            case "b" -> "aqua";
            case "c" -> "red";
            case "d" -> "pink";
            case "e" -> "yellow";
            case "f" -> "white";
            default -> code;
        };
    }
    
    private boolean setNickname(CommandSender sender, Player target, String nickname) {
        if (plugin.getNicknameManager() == null) {
            Text.sendErrorMessage(sender, "feature-disabled", plugin.getLangManager(), "nicknames", "are not available", true);
            return true;
        }
        
        boolean success = plugin.getNicknameManager().setNickname(target, nickname);
        
        if (success) {
            if (sender.equals(target)) {
                sender.sendMessage(PrismaticAPI.colorize("&aYour nickname has been set to: " + nickname));
            } else {
                sender.sendMessage(PrismaticAPI.colorize("&aSet " + target.getName() + "'s nickname to: " + nickname));
            }
        } else {
            sender.sendMessage(PrismaticAPI.colorize("&cInvalid nickname! Must be " + 
                plugin.getNicknameManager().getMinNickLength() + "-" + 
                plugin.getNicknameManager().getMaxNickLength() + " characters."));
        }
        
        return true;
    }
}
