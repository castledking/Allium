package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.TeleportModule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;

public final class ScaleCommand implements SpecCommandHandler {
    private final PluginStart plugin;

    public ScaleCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SCALE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        double scale = args.length == 0 ? Double.NaN : parseScale(args[0]);
        if (args.length > 2 || !Double.isFinite(scale)) {
            context.sendInvalidArgs(context.sender() instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }

        Player target = context.resolveSelfOrTarget(1);
        if (target == null) return;

        Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
        if (attribute == null) attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.scale"));
        if (attribute == null) {
            UtilitySupport.send(plugin, context.sender(), Messages.SCALE_UNAVAILABLE);
            return;
        }

        String value = BigDecimal.valueOf(scale).stripTrailingZeros().toPlainString();
        List<LivingEntity> mobs = args.length == 1
                && plugin.getModuleManager().getModule("teleport") instanceof TeleportModule teleport
                && teleport.isEnabled()
                ? teleport.companions().selectedMobs(target) : List.of();
        if (!mobs.isEmpty()) {
            int scaled = 0;
            for (LivingEntity mob : mobs) {
                if (setScale(mob, attribute, scale)) scaled++;
            }
            if (scaled == 0) {
                UtilitySupport.send(plugin, context.sender(), Messages.SCALE_UNAVAILABLE);
            } else {
                UtilitySupport.send(plugin, context.sender(), Messages.SCALE_MOBS,
                        "{count}", Integer.toString(scaled), "{scale}", value);
            }
            return;
        }

        if (!setScale(target, attribute, scale)) {
            UtilitySupport.send(plugin, context.sender(), Messages.SCALE_UNAVAILABLE);
            return;
        }
        UtilitySupport.send(plugin, context.sender(), Messages.SCALE_SET,
                "{target}", target.getName(), "{scale}", value);
    }

    private static double parseScale(String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value < 0 || value > 16) return Double.NaN;
            return value == 0 ? 0.1 : value;
        } catch (NumberFormatException invalid) {
            return Double.NaN;
        }
    }

    private static boolean setScale(LivingEntity target, Attribute attribute, double value) {
        var instance = target.getAttribute(attribute);
        if (instance == null) return false;
        instance.setBaseValue(value);
        return true;
    }
}
