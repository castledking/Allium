package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.compat.EntityCompatProvider;
import codes.castled.allium.compat.HealthCompatProvider;
import codes.castled.allium.economy.PaidCommandService;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

public final class HealCommand implements SpecCommandHandler.Immediate {

    private final PluginStart plugin;
    private final Supplier<PaidCommandService> paidCommands;

    public HealCommand(PluginStart plugin) {
        this(plugin, () -> null);
    }

    public HealCommand(PluginStart plugin, Supplier<PaidCommandService> paidCommands) {
        this.plugin = plugin;
        this.paidCommands = paidCommands == null ? () -> null : paidCommands;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.HEAL;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        PaidCommandService paid = paidCommands.get();
        Player target = context.resolveSelfOrTarget(0);
        if (target == null) {
            return;
        }
        if (paid != null && target == context.sender() && context.sender() instanceof Player self) {
            paid.execute(self, "heal", () -> true, () -> {
                applyHeal(context, self);
                return true;
            });
            return;
        }
        UtilitySupport.onTarget(plugin, context.sender(), target, () -> applyHeal(context, target));
    }

    @Override
    public boolean executeWithResult(BukkitCommandContext context) {
        Player target = context.resolveSelfOrTarget(0);
        if (target == null) {
            return false;
        }
        UtilitySupport.onTarget(plugin, context.sender(), target, () -> applyHeal(context, target));
        return true;
    }

    private void applyHeal(BukkitCommandContext context, Player target) {
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
