package codes.castled.allium.command.items;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * {@code /i <item[,item...]> [amount] [key:value...] [-e] [--overflow=...]} (alias {@code /item}):
 * self-use harness for {@link GiveCommand} using identical {@code allium.give} /
 * {@code allium.give.privileged} permissions and {@code ItemSpecParser} syntax.
 */
public final class ItemSelfCommand implements SpecCommandHandler {

    private final GiveCommand giveCommand;

    public ItemSelfCommand(PluginStart plugin, GiveCommand giveCommand) {
        Objects.requireNonNull(plugin, "plugin");
        this.giveCommand = Objects.requireNonNull(giveCommand, "giveCommand");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.ITEM;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            context.sendInvalidArgs(spec().consoleUsage());
            return;
        }
        String[] args = context.args();
        if (args.length < 1) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        giveCommand.give(player, List.of(player), String.join(" ", args));
    }
}
