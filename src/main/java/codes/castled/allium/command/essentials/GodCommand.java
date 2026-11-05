package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.compat.GodModeCompatProvider;
import org.bukkit.entity.Player;

public final class GodCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public GodCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.GOD;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        Player target = context.resolveSelfOrTarget(0);
        if (target == null) {
            return;
        }

        boolean enabled = !GodModeCompatProvider.current().isGodMode(target);
        GodModeCompatProvider.current().setGodMode(target, enabled);
        String state = enabled ? "&aenabled" : "&cdisabled";
        context.sendOutcome(context.sender(), "toggle", "{state}", state, "{player}", target.getName());
        if (target != context.sender()) {
            context.sendOutcome(target, "toggle", "{state}", state, "{player}", target.getName());
        }
    }
}
