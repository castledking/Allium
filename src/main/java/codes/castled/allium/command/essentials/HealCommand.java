package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.compat.EntityCompatProvider;
import codes.castled.allium.compat.HealthCompatProvider;
import codes.castled.allium.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class HealCommand implements CommandExecutor {

    private final PluginStart plugin;

    public HealCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("allium.heal")) {
            plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "heal");
            return true;
        }

        Player target;
        if (args.length > 0) {
            if (!sender.hasPermission("allium.heal.others")) {
                plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "heal for others");
                return true;
            }
            target = plugin.getServer().getPlayer(args[0]);
            if (target == null) {
                plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[0]);
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().send(sender, Messages.INVALID_ARGS, "{usage}", "/heal <player>");
                return true;
            }
            target = player;
        }

        double maxHealth = HealthCompatProvider.current().getMaxHealth(target);
        target.setHealth(maxHealth);
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setFireTicks(0);

        EntityCompatProvider.current().clearPotionEffects(target);
        EntityCompatProvider.current().clearArrows(target);
        EntityCompatProvider.current().clearFreeze(target);

        if (target != sender) {
            plugin.getMessageManager().send(sender, Messages.HEAL_OTHER, "{player}", target.getName());
        }
        plugin.getMessageManager().send(target, Messages.HEAL);
        return true;
    }
}
