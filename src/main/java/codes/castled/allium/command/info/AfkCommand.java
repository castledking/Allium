package codes.castled.allium.command.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.info.PlayerInfoService;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

import java.util.Objects;

/** {@code /afk [message]} (alias {@code /away}): toggles AFK state with optional reason. */
public final class AfkCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final PlayerInfoService info;

    public AfkCommand(PluginStart plugin, PlayerInfoService info) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.info = Objects.requireNonNull(info, "info");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.AFK;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            plugin.getMessageManager().send(context.sender(), Messages.INFO_PLAYER_ONLY, "{cmd}", "afk");
            return;
        }
        String[] args = context.args();
        String message = args.length == 0 ? null : String.join(" ", args);
        info.toggleAfk(player, message);
    }
}
