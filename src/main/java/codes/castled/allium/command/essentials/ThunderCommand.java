package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandArgSpec;
import codes.castled.allium.command.spec.CommandMessageKeys;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.SuggestionSpec;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class ThunderCommand implements SpecCommandHandler {

    private static final String USAGE = "/thunder <true|false> [duration]";
    public static final CommandSpec SPEC = new CommandSpec(
            "thunder", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of(),
            "Set thunder in your world, optionally for a number of seconds",
            USAGE, USAGE, "allium.thunder", null,
            List.of(CommandArgSpec.required("enabled", null, SuggestionSpec.literals(List.of("true", "false"), null)),
                    CommandArgSpec.optional("duration", null, SuggestionSpec.none())),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    private final PluginStart plugin;

    public ThunderCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (args.length == 0 || args.length > 2
                || !args[0].equalsIgnoreCase("true") && !args[0].equalsIgnoreCase("false")) {
            context.sendInvalidArgs(USAGE);
            return;
        }
        int ticks = 0;
        if (args.length == 2) {
            try {
                ticks = Math.multiplyExact(Integer.parseInt(args[1]), 20);
            } catch (NumberFormatException | ArithmeticException invalid) {
                ticks = 0;
            }
            if (ticks <= 0) {
                UtilitySupport.send(plugin, player, Messages.NUMBER_INVALID, "{input}", args[1]);
                return;
            }
        }
        boolean enabled = Boolean.parseBoolean(args[0]);
        var world = player.getWorld();
        world.setThundering(enabled);
        if (ticks > 0) {
            world.setThunderDuration(ticks);
        }
        UtilitySupport.send(plugin, player, enabled ? Messages.THUNDER_ON : Messages.THUNDER_OFF,
                "{world}", world.getName());
    }
}
