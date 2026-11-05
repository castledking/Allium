package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.compat.EntityCompatProvider;
import codes.castled.allium.compat.HealthCompatProvider;
import org.bukkit.entity.Player;

public final class HealCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public HealCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.HEAL;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        Player target = context.resolveSelfOrTarget(0);
        if (target == null) {
            return;
        }

        double maxHealth = HealthCompatProvider.current().getMaxHealth(target);
        target.setHealth(maxHealth);
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setFireTicks(0);

        EntityCompatProvider.current().clearPotionEffects(target);
        EntityCompatProvider.current().clearArrows(target);
        EntityCompatProvider.current().clearFreeze(target);

        if (target != context.sender()) {
            context.sendOutcome(context.sender(), "other", "{player}", target.getName());
        }
        context.sendOutcome(target, "self");
    }
}
