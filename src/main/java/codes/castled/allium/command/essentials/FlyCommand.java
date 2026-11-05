package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class FlyCommand implements CommandExecutor {

    private final PluginStart plugin;

    public FlyCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("allium.fly")) {
            plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "fly");
            return true;
        }

        Player target;
        if (args.length > 0) {
            if (!sender.hasPermission("allium.fly.others")) {
                plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "fly for others");
                return true;
            }
            target = plugin.getServer().getPlayer(args[0]);
            if (target == null) {
                plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[0]);
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().send(sender, Messages.INVALID_ARGS, "{usage}", "/fly <player>");
                return true;
            }
            target = player;
        }

        boolean enabled = !target.getAllowFlight();
        target.setAllowFlight(enabled);
        target.setFlying(enabled);

        String state = enabled ? "&aenabled" : "&cdisabled";
        plugin.getMessageManager().send(sender, Messages.FLY_TOGGLE, "{state}", state, "{player}", target.getName());
        if (target != sender) {
            plugin.getMessageManager().send(target, Messages.FLY_TOGGLE, "{state}", state, "{player}", target.getName());
        }
        return true;
    }
}
