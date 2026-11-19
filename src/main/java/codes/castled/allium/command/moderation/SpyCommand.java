package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.moderation.StaffSpyService;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.entity.Player;

/** {@code /spy <player> [toggle <messages|local|commands>]}: interactive spy panel with clickable toggles. */
public final class SpyCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final StaffSpyService spy;

    public SpyCommand(PluginStart plugin, StaffSpyService spy) {
        this.plugin = plugin;
        this.spy = spy;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SPY;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player viewer)) {
            plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (args.length != 1 && !(args.length == 3 && args[1].equalsIgnoreCase("toggle"))) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !PlayerNameVisibility.canSee(viewer, target)) {
            plugin.getMessageManager().send(viewer, Messages.PLAYER_NOT_FOUND, "{player}", MessagingService.safeName(args[0]));
            return;
        }
        if (args.length == 3) {
            spy.toggleTarget(viewer, target, args[2]);
        } else {
            spy.openPanel(viewer, target);
        }
    }
}
