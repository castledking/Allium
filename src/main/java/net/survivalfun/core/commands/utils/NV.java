package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
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
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check if player has permission
        if (!player.hasPermission("core.nv")) {
            Text.sendErrorMessage(player, "no-permission", lang);
            return true;
        }

        // Check if player is in spectator mode
        if (player.getGameMode() != GameMode.SPECTATOR) {
            // For other gamemodes, check for gamemode-specific permission
            String gameModePermission = "core.gamemode." + player.getGameMode().name().toLowerCase() + ".nv";

            if (!player.hasPermission(gameModePermission)) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}",
                        label + " in " + player.getGameMode().name().toLowerCase() + ".");

                return true; // Block command execution
            }

        }



        // Toggle night vision
        this.toggleNightVision(player);
        return true;
    }

    /**
     * Toggles night vision effect for a player
     *
     * @param player The player to toggle night vision for
     */
    public void toggleNightVision(Player player) {
        // Check if player already has night vision
        boolean hasNightVision = player.hasPotionEffect(PotionEffectType.NIGHT_VISION);

        if (hasNightVision) {
            // Remove night vision
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            player.sendMessage(lang.get("nv.disabled"));
        } else {
            // Add night vision with infinite duration (999999 seconds = ~11.5 days)
            // Using a very high amplifier (9) for maximum brightness
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.NIGHT_VISION,
                    999999 * 20, // Duration in ticks (20 ticks = 1 second)
                    0,           // Amplifier (0 = level 1)
                    false,       // No ambient particles
                    false,       // No particles
                    true         // Show icon in inventory
            ));
            player.sendMessage(lang.get("nv.enabled"));
        }
    }
}
