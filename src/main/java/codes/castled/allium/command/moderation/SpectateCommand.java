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

/** {@code /spectate <player>}: runs the configured {@code spectate-commands} in console. */
public final class SpectateCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final StaffSpyService spy;

    public SpectateCommand(PluginStart plugin, StaffSpyService spy) {
        this.plugin = plugin;
        this.spy = spy;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SPECTATE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player staff)) {
            plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (args.length != 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !PlayerNameVisibility.canSee(staff, target)) {
            plugin.getMessageManager().send(staff, Messages.PLAYER_NOT_FOUND, "{player}", MessagingService.safeName(args[0]));
            return;
        }
        spy.spectate(staff, target);
    }
}
