package net.survivalfun.core.commands.utils.core.managers;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
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
    /**
     * Registers all gamemode command aliases with the plugin.
     */
    public void register() {
        // Register the main gamemode command if it exists in plugin.yml
        PluginCommand gamemodeCmd = plugin.getCommand("gamemode");
        if (gamemodeCmd != null) {
            gamemodeCmd.setExecutor(this);
        }

        try {
            // Get the server's command map via reflection
            final Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

            // Register all aliases in the commandAliases map
            for (String alias : commandAliases.keySet()) {
                GameMode mode = commandAliases.get(alias);

                // Create a new command for each alias
                Command newCommand = new Command(alias) {
                    @Override
                    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                        return Gamemode.this.onCommand(sender, this, commandLabel, args);
                    }
                };

                // Set command properties
                newCommand.setDescription("Switches to " + mode.name().toLowerCase() + " mode");

                // Register command with the server's command map
                commandMap.register(plugin.getName(), newCommand);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to register commands: " + e.getMessage());
            e.printStackTrace();
        }
    }



    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if(!sender.hasPermission("core.gamemode")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
            return true;
        }
        // Check if using a direct gamemode command (gmc, gms, gma, gmsp)
        GameMode directMode = commandAliases.get(label.toLowerCase());
        if (directMode != null) {
            return handleDirectGamemodeCommand(sender, directMode, args, label);
        }

        // Standard gamemode command handling
        if (args.length == 0 || args.length > 2) {
            sender.sendMessage(lang.get("command-usage")
                    .replace("{cmd}", "gm")
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
            String commandName = mode.toString().toLowerCase() + " mode";
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", commandName);
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
            // Check if sender has permission to change other players' gamemod
            if (!sender.hasPermission("core.gamemode.others")) {
                String commandName = "set " + mode.toString().toLowerCase() + " mode for others.";
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", commandName);
                return true;
            }

            // Attempt to find target player
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
                return true;
            }
        }

        setGamemode(target, mode, sender);
        return true;
    }

    private boolean handleDirectGamemodeCommand(CommandSender sender, GameMode mode, String[] args, String label) {
        // Check if sender has permission for this gamemode
        if (!hasGameModePermission(sender, mode)) {
            String commandName = mode.toString().toLowerCase() + " mode";
            Text.sendErrorMessage(sender, "no-permission", lang, "/{cmd}", commandName, true);
            return true;
        }

        Player target;

        // No arguments - set gamemode for sender
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "[player]"));
                return true;
            }
            target = (Player) sender;
        }
        // One argument - set gamemode for specified player
        else if (args.length == 1) {
            // Check if sender has permission to change other players' gamemode
            if (!sender.hasPermission("core.gamemode.others")) {
                String gamemodeOthers = "set " + mode.toString().toLowerCase() + " mode for others.";
                Text.sendErrorMessage(sender, "no-permission", lang, "/{cmd}", gamemodeOthers, true);
                return true;
            }

            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
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
            // For self, remove the name placeholder
            sender.sendMessage(lang.get("gamemode.switch")
                .replace("{name}", player.getName())
                .replace("{mode}", mode.toString()));
        } else {
            // For others, show the player's name
            sender.sendMessage(lang.get("gamemode.switch")
                .replace("{name}", player.getName())
                .replace("{mode}", mode.toString()));
                
            // Also notify the player
            String message = lang.get("gamemode.switch")
            .replaceAll("\\s+([&§][0-9a-fk-orA-FK-OR])*\\{name\\}", "")
            .replace("{mode}", mode.toString());
            player.sendMessage(message);
        }
    }



}
