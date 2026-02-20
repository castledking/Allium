package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Speed implements CommandExecutor {
    private final Lang lang;

    // Valid speed types and values
    private static final List<String> SPEED_TYPES = Arrays.asList("walk", "fly", "1", "1.5", "1.75", "2");
    private static final List<String> SPEED_VALUES = Arrays.asList("1", "1.5", "1.75", "2");

    public Speed(PluginStart plugin) {
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("speed")) {
            if (args.length == 0) {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "[type] <speed> [player]"));
                return true;
            }

            boolean firstArgNumeric = isNumeric(args[0]);
            if (!firstArgNumeric && args.length < 2) {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", label)
                        .replace("{args}", "[type] <speed> [player]"));
                return true;
            }

            // Handle command execution
            return handleSpeedCommand(sender, args);
        }
        return false;
    }

    private boolean handleSpeedCommand(CommandSender sender, String[] args) {
        // Check if first argument is a number (speed value)
        boolean isNumericSpeed = isNumeric(args[0]);

        if (isNumericSpeed) {
            // If first arg is numeric, use player's current flying state to determine speed type
            if (!(sender instanceof Player player)) {
                sender.sendMessage(lang.get("command-usage")
                        .replace("{cmd}", "speed")
                        .replace("{args}", "[type] <speed> [player]"));
                return true;
            }

            boolean isFly = determineSpeedTypeFromPlayerState(player);
            float speed;
            try {
                speed = getMoveSpeed(args[0]);
            } catch (IllegalArgumentException e) {
                Text.sendErrorMessage(sender, "invalid", lang, "{arg}",
                        "&cspeed value: &7" + args[0] + "&c.",
                        "{syntax}", "&cMust be a number between 0.01 and 10.");
                return true;
            }

            // Check if targeting another player
            if (args.length > 1) {
                if (!sender.hasPermission("allium.speed.others")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "speed on others");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[1]);
                    return true;
                }

                setPlayerSpeed(target, sender, isFly, speed, args[1]);
            } else {
                if (!player.hasPermission("allium.speed")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "speed");
                    return true;
                }

                setPlayerSpeed(player, sender, isFly, speed, player.getName());
            }
        } else {
            // Traditional mode: first arg is type, second is speed
            boolean isFly = isFlyMode(args[0]);

            // Parse speed value
            float speed;
            try {
                speed = getMoveSpeed(args[1]);
            } catch (IllegalArgumentException e) {
                Text.sendErrorMessage(sender, "invalid", lang, "{arg}",
                 "&cspeed value: &7" + args[1] + "&c.",
                 "{syntax}", "&cMust be a number between 0.01 and 10.");
                return true;
            }

            // Check if targeting another player
            if (args.length > 2) {
                if (!sender.hasPermission("allium.speed.others")) {
                    Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", "speed on others");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[2]);
                    return true;
                }

                setPlayerSpeed(target, sender, isFly, speed, args[2]);
            } else {
                // Target self
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(lang.get("command-usage")
                            .replace("{cmd}", "speed")
                            .replace("{args}", "[type] <speed> [player]"));
                    return true;
                }

                if (!player.hasPermission("allium.speed")) {
                    Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "speed");
                    return true;
                }

                setPlayerSpeed(player, sender, isFly, speed, player.getName());
            }
        }

        return true;
    }

    private void setPlayerSpeed(Player player, CommandSender sender, boolean isFly, float speed, String playerName) {
        float realSpeed = getRealMoveSpeed(speed, isFly);

        if (isFly) {
            player.setFlySpeed(realSpeed);
            sendSpeedMessage(sender, "flying", speed, playerName);
        } else {
            player.setWalkSpeed(realSpeed);
            sendSpeedMessage(sender, "walking", speed, playerName);
        }
    }

    private void sendSpeedMessage(CommandSender sender, String type, float speed, String playerName) {
        // Create the message with placeholders
        String message = lang.get("speed.set")
                .replace("{type}", type)
                .replace("{speed}", String.valueOf(speed));

        // Remove {name} when it's a self speed change (empty string replacement)
        if (sender instanceof Player && ((Player) sender).getName().equals(playerName)) {
            message = message.replace(" {name}", "");
        } else {
            message = message.replace("{name}", " for " + playerName);
        }

        // Send the message with sound support
        lang.sendMessage(sender, message);
    }

    private boolean isNumeric(String str) {
        try {
            Float.parseFloat(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean determineSpeedTypeFromPlayerState(Player player) {
        // First check current in-game flying state
        if (player.isFlying()) {
            return true;
        }

        // Treat spectator mode as flying for speed purposes
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }

        // Default to walking speed
        return false;
    }

    private boolean isFlyMode(String modeString) {
        if (modeString == null) {
            return false;
        }
        String normalized = modeString.toLowerCase();
        if (normalized.contains("fly") || normalized.equals("f")) {
            return true;
        }
        if (normalized.contains("walk") || normalized.contains("run") || normalized.equals("w") || normalized.equals("r")) {
            return false;
        }
        throw new IllegalArgumentException("Unknown speed type: " + modeString);
    }
    private float getMoveSpeed(String moveSpeed) throws IllegalArgumentException {
        final float minSpeed = 0.01f;
        final float maxSpeed = 10f;

        try {
            float userSpeed = Float.parseFloat(moveSpeed);

            if (Float.isNaN(userSpeed) || Float.isInfinite(userSpeed)) {
                throw new IllegalArgumentException("Invalid speed value: " + moveSpeed);
            }

            if (userSpeed < minSpeed) {
                throw new IllegalArgumentException("Speed below minimum threshold: " + moveSpeed);
            }

            if (userSpeed > maxSpeed) {
                userSpeed = maxSpeed;
            }

            return userSpeed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid speed value: " + moveSpeed);
        }
    }

    private float getRealMoveSpeed(float userSpeed, boolean isFly) {
        final float defaultSpeed = isFly ? 0.1f : 0.2f;
        float maxSpeed = 1f;

        // In Allium, we don't have a bypass system like Essentials, so we'll use the max speed
        if (userSpeed < 1f) {
            return defaultSpeed * userSpeed;
        } else {
            final float ratio = ((userSpeed - 1) / 9) * (maxSpeed - defaultSpeed);
            return ratio + defaultSpeed;
        }
    }

    /**
     * Get tab completion options for the speed command
     */
    public List<String> getTabCompleteOptions(String[] args) {
        if (args.length == 1) {
            // For first argument, suggest both types and numbers
            List<String> suggestions = new ArrayList<>(SPEED_TYPES);
            suggestions.addAll(SPEED_VALUES);
            return suggestions;
        } else if (args.length == 2) {
            // Second argument depends on first argument
            if (isNumeric(args[0])) {
                // If first arg is numeric, second arg should be a player name
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(java.util.stream.Collectors.toList());
            } else {
                // Traditional mode: second arg is speed value
                return SPEED_VALUES;
            }
        } else if (args.length == 3) {
            // Third argument is always a player name for the "others" permission case
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }
}
