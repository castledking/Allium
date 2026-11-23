package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

public final class NvCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public NvCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.NV;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length > 2) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }

        Boolean explicitState = args.length >= 1 ? parseState(args[0]) : null;
        Player target;
        if (args.length == 0) {
            if (!(context.sender() instanceof Player self)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            target = self;
        } else if (args.length == 1) {
            if (explicitState != null) {
                if (!(context.sender() instanceof Player self)) {
                    context.sendInvalidArgs(spec().consoleUsage());
                    return;
                }
                target = self;
            } else {
                target = context.resolveSelfOrTarget(0);
                if (target == null) {
                    return;
                }
            }
        } else {
            if (explicitState == null) {
                context.sendInvalidArgs(spec().selfUsage());
                return;
            }
            target = context.resolveSelfOrTarget(1);
            if (target == null) {
                return;
            }
        }

        UtilitySupport.onTarget(plugin, context.sender(), target, () -> {
            boolean enable = explicitState != null
                    ? explicitState
                    : !target.hasPotionEffect(PotionEffectType.NIGHT_VISION);
            if (enable) {
                target.addPotionEffect(new PotionEffect(
                        PotionEffectType.NIGHT_VISION,
                        PotionEffect.INFINITE_DURATION,
                        0,
                        false,
                        false,
                        false
                ));
            } else {
                target.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }

            String state = enable ? "&aenabled" : "&cdisabled";
            UtilitySupport.send(plugin, context.sender(), Messages.UTILITY_NV_TOGGLE,
                    "{state}", state,
                    "{player}", target.getName());
            if (target != context.sender()) {
                UtilitySupport.send(plugin, target, Messages.UTILITY_NV_TOGGLE,
                        "{state}", state,
                        "{player}", target.getName());
                UtilitySupport.audit(plugin, context.sender(), "nv",
                        "target=" + target.getName() + " state=" + enable);
            }
        });
    }

    private static Boolean parseState(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "true" -> Boolean.TRUE;
            case "off", "disable", "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
