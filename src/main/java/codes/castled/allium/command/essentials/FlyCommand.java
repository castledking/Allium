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
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        if (!player.hasPermission("allium.fly")) {
            plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "fly");
            return true;
        }

        Player target = args.length > 0 ? plugin.getServer().getPlayer(args[0]) : player;
        if (target == null) {
            plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[0]);
            return true;
        }

        boolean enabled = !target.getAllowFlight();
        target.setAllowFlight(enabled);
        target.setFlying(enabled);

        String state = enabled ? "&aenabled" : "&cdisabled";
        plugin.getMessageManager().send(sender, Messages.FLY_TOGGLE, "{state}", state, "{player}", target.getName());
        return true;
    }
}
