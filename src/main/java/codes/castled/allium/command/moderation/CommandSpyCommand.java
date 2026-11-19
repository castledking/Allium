package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.moderation.StaffSpyService;
import org.bukkit.entity.Player;

/** {@code /commandspy [on|off]}: opt-in toggle to view other players' commands. */
public final class CommandSpyCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final StaffSpyService spy;

    public CommandSpyCommand(PluginStart plugin, StaffSpyService spy) {
        this.plugin = plugin;
        this.spy = spy;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.COMMANDSPY;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (args.length > 1 || (args.length == 1 && !args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off"))) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Boolean desired = args.length == 0 ? null : args[0].equalsIgnoreCase("on");
        spy.setGlobalCommandSpy(player, desired);
    }
}
