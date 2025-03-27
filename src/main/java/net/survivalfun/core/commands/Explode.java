package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class Explode implements CommandExecutor {

    private final PluginStart plugin; // Reference to the main plugin
    private final FileConfiguration config;

    // Config values for min, max, and default explosion power
    private int MIN_EXPLOSION_POWER;
    private int MAX_EXPLOSION_POWER;
    private int DEFAULT_EXPLOSION_POWER;

    /**
     * Constructor to pass and initialize the PluginStart instance and config values.
     *
     * @param plugin The main plugin instance.
     */
    public Explode(PluginStart plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();

        // Load the explosion config values
        loadConfigValues();
    }

    /**
     * Load config values for explosion power.
     */
    private void loadConfigValues() {
        this.MIN_EXPLOSION_POWER = config.getInt("explode-command.min", 1);
        this.MAX_EXPLOSION_POWER = config.getInt("explode-command.max", 10);
        this.DEFAULT_EXPLOSION_POWER = config.getInt("explode-command.default", 2);
    }

    /**
     * Command logic implementation.
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Ensure the command is executed by a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be run by a player!");
            return true;
        }

        // Executor of the command
        int explosionPower = DEFAULT_EXPLOSION_POWER; // Default explosion power
        Player targetPlayer = player; // Default target is the executor

        if (args.length >= 1) {
            // Check if the first argument is a valid explosion power
            try {
                explosionPower = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                // It’s not a number, try to find the player
                targetPlayer = plugin.getServer().getPlayer(args[0]);
                if (targetPlayer == null) {
                    String message = plugin.getLangManager().format(
                            plugin.getLangManager().get("player-not-found"),
                            Map.of("player", args[0])
                    );
                    player.sendMessage(message);
                    return true;
                }
            }
        }

        if (args.length >= 2) {
            // Attempt to parse the explosion power from the second argument
            try {
                explosionPower = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                String message = plugin.getLangManager().format(
                        plugin.getLangManager().get("invalid-explode-integer"),
                        Map.of("min", String.valueOf(MIN_EXPLOSION_POWER),
                                "max", String.valueOf(MAX_EXPLOSION_POWER))
                );
                player.sendMessage(message);
                return true;
            }
        }

        // Enforce explosion power limits
        if (explosionPower < MIN_EXPLOSION_POWER || explosionPower > MAX_EXPLOSION_POWER) {
            String message = plugin.getLangManager().format(
                    plugin.getLangManager().get("invalid-explode-integer"),
                    Map.of("min", String.valueOf(MIN_EXPLOSION_POWER),
                            "max", String.valueOf(MAX_EXPLOSION_POWER))
            );
            player.sendMessage(message);
            return true;
        }

        // Check if the player has permission to explode themselves
        if (targetPlayer.equals(player) && !player.hasPermission("core.explode.self")) {
            String noPermissionMessage = plugin.getLangManager().get("no-permission");
            player.sendMessage(noPermissionMessage);
            return true;
        }

        // Check if the player has permission to explode others
        if (!targetPlayer.equals(player) && !player.hasPermission("core.explode.other")) {
            player.sendMessage("You do not have permission to explode other players!");
            return true;
        }

        // Call the method to create the fireball at the target player's location
        createFireball(targetPlayer, explosionPower);

        // Use appropriate success message based on the target player
        String message;
        if (targetPlayer.equals(player)) {
            message = plugin.getLangManager().format(
                    plugin.getLangManager().get("explode-success"),
                    Map.of("power", String.valueOf(explosionPower))
            );
        } else {
            message = plugin.getLangManager().format(
                    plugin.getLangManager().get("explode-success-other"),
                    Map.of("player", targetPlayer.getName(), "power", String.valueOf(explosionPower))
            );
        }

        player.sendMessage(message);
        return true;
    }




    /**
     * Creates a fireball for a player at the player's location with the given explosion power.
     *
     * @param player         The target player.
     * @param explosionPower The explosion power of the fireball.
     */
    public void createFireball(Player player, int explosionPower) {
        player.getWorld().createExplosion(player.getLocation(), explosionPower);
    }
}