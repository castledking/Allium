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
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        if (!player.hasPermission("allium.heal")) {
            plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "heal");
            return true;
        }

        Player target = args.length > 0 ? plugin.getServer().getPlayer(args[0]) : player;
        if (target == null) {
            plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[0]);
            return true;
        }

        double maxHealth = HealthCompatProvider.current().getMaxHealth(target);
        target.setHealth(Math.min(target.getHealth(), maxHealth));
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setFireTicks(0);
        
        EntityCompatProvider.current().clearPotionEffects(target);
        EntityCompatProvider.current().clearArrows(target);
        EntityCompatProvider.current().clearFreeze(target);

        if (target != player) {
            plugin.getMessageManager().send(sender, Messages.HEAL_OTHER, "{player}", target.getName());
        }
        plugin.getMessageManager().send(target, Messages.HEAL);
        return true;
    }
}
