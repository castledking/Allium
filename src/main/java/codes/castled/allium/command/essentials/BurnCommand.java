package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

public final class BurnCommand implements SpecCommandHandler {

    public static final int DEFAULT_SECONDS = 5;
    public static final int MAX_SECONDS = 300;

    private final PluginStart plugin;

    public BurnCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.BURN;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length < 1 || args.length > 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Player target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, context.sender(), args[0]);
        if (target == null) {
            return;
        }
        int seconds = DEFAULT_SECONDS;
        if (args.length == 2) {
            try {
                seconds = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                UtilitySupport.send(plugin, context.sender(), Messages.NUMBER_INVALID, "{input}", args[1]);
                return;
            }
            if (seconds < 1 || seconds > MAX_SECONDS) {
                UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_BURN_INVALID);
                return;
            }
        }
        target.setFireTicks(seconds * 20);
        UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_BURN,
                "{player}", target.getName(),
                "{seconds}", Integer.toString(seconds));
        UtilitySupport.audit(plugin, context.sender(), "burn",
                "target=" + target.getName() + " seconds=" + seconds);
    }
}
