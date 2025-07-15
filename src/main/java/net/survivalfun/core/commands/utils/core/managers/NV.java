package net.survivalfun.core.commands.utils.core.managers;

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

        // /nv <player> (toggle for others)
        if (args.length == 1) {
            if (!player.hasPermission("allium.nv.others")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", label + " others");
                return true;
            }
            Player target = plugin.getServer().getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
                return true;
            }
            boolean enabled = toggleNightVision(target);
            String lastColor = lang.getFirstColorCode("nv.toggle");
            if (enabled) {
                player.sendMessage(lang.get("nv.toggle")
                        .replace("{state}", lang.get("styles.state.true") + "enabled" + lastColor)
                        .replace("{player}", target.getName()));
                target.sendMessage(lang.get("nv.toggle")
                        .replace("{state}", lang.get("styles.state.true") + "enabled" + lastColor)
                        .replace("{player}", target.getName()));
            } else {
                player.sendMessage(lang.get("nv.toggle")
                        .replace("{state}", lang.get("styles.state.false") + "disabled" + lastColor)
                        .replace("{player}", target.getName()));
                target.sendMessage(lang.get("nv.toggle")
                        .replace("{state}", lang.get("styles.state.false") + "disabled" + lastColor)
                        .replace("{player}", target.getName()));
            }
            return true;
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
        String lastColor = lang.getFirstColorCode("nv.toggle");
        if (enabled) {
            player.sendMessage(lang.get("nv.toggle")
                    .replace("{state}", lang.get("styles.state.true") + "enabled" + lastColor)
                    .replace("{name}", player.getName()));
        } else {
            player.sendMessage(lang.get("nv.toggle")
                    .replace("{state}", lang.get("styles.state.false") + "disabled" + lastColor)
                    .replace("{name}", player.getName()));
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
            // Add night vision with infinite duration (999999 seconds = ~11.5 days)
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.NIGHT_VISION,
                    999999 * 20, // Duration in ticks (20 ticks = 1 second)
                    0,           // Amplifier (0 = level 1)
                    false,       // No ambient particles
                    false,       // No particles
                    true         // Show icon in inventory
            ));
            return true;
        }
    }
}
