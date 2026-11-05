package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.compat.GodModeCompatProvider;
import codes.castled.allium.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class GodCommand implements CommandExecutor {

    private final PluginStart plugin;

    public GodCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        if (!player.hasPermission("allium.god")) {
            plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "god");
            return true;
        }

        Player target = args.length > 0 ? plugin.getServer().getPlayer(args[0]) : player;
        if (target == null) {
            plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", args[0]);
            return true;
        }

        boolean enabled = !GodModeCompatProvider.current().isGodMode(target);
        GodModeCompatProvider.current().setGodMode(target, enabled);
        String state = enabled ? "&aenabled" : "&cdisabled";
        plugin.getMessageManager().send(sender, Messages.GOD_TOGGLE, "{state}", state, "{player}", target.getName());
        return true;
    }
}
