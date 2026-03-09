package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/**
 * Command to toggle night vision for players in spectator mode
 */
public class NV implements CommandExecutor {

    private final PluginStart plugin;
    private final Lang lang;

    public NV(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            return handleConsoleCommand(sender, label, args);
        }

        // /nv <player> (toggle for others)
        if (args.length == 1) {
            return handleOtherPlayer(player, label, args[0]);
        }

        // Check if player has permission
        if (!player.hasPermission("allium.nv")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label);
            return true;
        }

        // Check if player is in spectator mode
        if (player.getGameMode() != GameMode.SPECTATOR) {
            // For other gamemodes, check for gamemode-specific permission
            String gameModePermission = "allium.gamemode." + player.getGameMode().name().toLowerCase() + ".nv";
            String nvGameModePermission = "allium.nv." + player.getGameMode().name().toLowerCase();

            if (!player.hasPermission(gameModePermission) && !player.hasPermission(nvGameModePermission)) {
                Text.sendErrorMessage(player, "no-permission", lang, "use /{cmd}",
                        "toggle night vision in " + player.getGameMode().name().toLowerCase() + ".", true);
                return true; // Block command execution
            }
        }

        // Toggle night vision for self
        boolean enabled = toggleNightVision(player);
        String firstColor = lang.getFirstColorCode("nv.toggle");
        String stateValue = enabled
            ? lang.get("styles.state.true") + "enabled" + firstColor
            : lang.get("styles.state.false") + "disabled" + firstColor;

        lang.sendMessage(player, "nv.toggle", "state", stateValue, "name", "");

        return true;
    }

    /**
     * Handles console command execution
     */
    private boolean handleConsoleCommand(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            lang.sendMessage(sender, "command-usage",
                "cmd", label,
                "args", "<player>"
            );
            return true;
        }
        
        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            if (Bukkit.getOfflinePlayer(args[0]).hasPlayedBefore()) {
                Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", args[0]);
            } else {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", args[0]);
            }
            return true;
        }
        
        boolean enabled = toggleNightVision(target);
        String firstColor = lang.getFirstColorCode("nv.toggle");
        String stateValue = enabled
            ? lang.get("styles.state.true") + "enabled" + firstColor
            : lang.get("styles.state.false") + "disabled" + firstColor;

        lang.sendMessage(sender, "nv.toggle.other", "state", stateValue, "name", target.getName());
        lang.sendMessage(target, "nv.toggle", "state", stateValue, "name", "");

        return true;
    }
    
    /**
     * Handles toggling night vision for another player
     */
    private boolean handleOtherPlayer(Player sender, String label, String targetName) {
        if (!sender.hasPermission("allium.nv.others")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label + " on others");
            return true;
        }
        
        Player target = plugin.getServer().getPlayer(targetName);
        if (target == null) {
            if (Bukkit.getOfflinePlayer(targetName).hasPlayedBefore()) {
                Text.sendErrorMessage(sender, "player-not-online", lang, "{name}", targetName);
            } else {
                Text.sendErrorMessage(sender, "player-not-found", lang, "{name}", targetName);
            }
            return true;
        }
        
        // Check if target is in spectator mode or has permission
        if (target.getGameMode() != GameMode.SPECTATOR) {
            String gameModePermission = "allium.gamemode." + target.getGameMode().name().toLowerCase() + ".nv";
            String nvGameModePermission = "allium.nv." + target.getGameMode().name().toLowerCase();
            
            if (!target.hasPermission(gameModePermission) && !target.hasPermission(nvGameModePermission)) {
                Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", 
                    "toggle night vision for players in " + target.getGameMode().name().toLowerCase() + " mode");
                return true;
            }
        }
        
        boolean enabled = toggleNightVision(target);
        String firstColor = lang.getFirstColorCode("nv.toggle");
        String stateValue = enabled
            ? lang.get("styles.state.true") + "enabled" + firstColor
            : lang.get("styles.state.false") + "disabled" + firstColor;

        lang.sendMessage(sender, "nv.toggle.other", "state", stateValue, "name", target.getName());
        if (!sender.equals(target)) {
            lang.sendMessage(target, "nv.toggle", "state", stateValue, "name", "");
        }

        return true;
    }
    
    /**
     * Toggles night vision effect for a player
     *
     * @param player The player to toggle night vision for
     * @return true if enabled, false if disabled
     */
    public boolean toggleNightVision(Player player) {
        // Check if player already has night vision
        boolean hasNightVision = player.hasPotionEffect(PotionEffectType.NIGHT_VISION);

        if (hasNightVision) {
            // Remove night vision
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            return false;
        } else {
            // Add night vision with infinite duration
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, true));
            return true;
        }
    }
}
