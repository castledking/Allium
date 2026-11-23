package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.TeleportModule;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.math.BigDecimal;

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
        scale(context.sender(), target, attribute, scale, value, args.length == 1);
    }

    private void scale(CommandSender sender, Player target, Attribute attribute, double scale, String value, boolean useSelection) {
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), target, () -> {
            if (BukkitServerThread.isFolia() && !target.isOnline()) return;
            if (useSelection
                    && plugin.getModuleManager().getModule("teleport") instanceof TeleportModule teleport
                    && teleport.isEnabled()) {
                teleport.companions().applySelectedMobs(target, mob -> setScale(mob, attribute, scale))
                        .thenAccept(result -> {
                            Runnable report = () -> {
                                if (BukkitServerThread.isFolia() && !target.isOnline()) return;
                                if (result.eligible() == 0) {
                                    scalePlayer(target, attribute, scale, value, sender);
                                } else if (result.changed() == 0) {
                                    UtilitySupport.send(plugin, sender, Messages.SCALE_UNAVAILABLE);
                                } else {
                                    UtilitySupport.send(plugin, sender, Messages.SCALE_MOBS,
                                            "{count}", Integer.toString(result.changed()), "{scale}", value);
                                }
                            };
                            if (BukkitServerThread.isFolia()) {
                                BukkitServerThread.runFor(plugin, plugin.getScheduler(), target, report, () -> {}, 0);
                            } else report.run();
                        });
            } else scalePlayer(target, attribute, scale, value, sender);
        });
    }

    private void scalePlayer(Player target, Attribute attribute, double scale, String value, CommandSender sender) {
        boolean scaled = setScale(target, attribute, scale);
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), sender, () -> {
            if (scaled) UtilitySupport.send(plugin, sender, Messages.SCALE_SET, "{target}", target.getName(), "{scale}", value);
            else UtilitySupport.send(plugin, sender, Messages.SCALE_UNAVAILABLE);
        });
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
