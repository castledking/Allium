package codes.castled.allium.command.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * {@code /vanish [on|off] [player]} (alias {@code /v}).
 */
public final class VanishCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final ModerationService moderation;

    public VanishCommand(PluginStart plugin, ModerationService moderation) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.moderation = Objects.requireNonNull(moderation, "moderation");
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.VANISH;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();

        Boolean desiredState = null;
        String targetName = null;

        if (args.length == 1) {
            desiredState = parseState(args[0]);
            if (desiredState == null) {
                targetName = args[0];
            }
        } else if (args.length == 2) {
            desiredState = parseState(args[0]);
            if (desiredState == null) {
                context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
                return;
            }
            targetName = args[1];
        } else if (args.length > 2) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }

        Player target;
        if (targetName == null) {
            if (!(sender instanceof Player self)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            target = self;
        } else {
            if (!sender.hasPermission(ModerationService.PERMISSION_VANISH_OTHERS)) {
                plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "vanish for others");
                return;
            }
            target = plugin.getServer().getPlayerExact(targetName);
            if (target == null || !PlayerNameVisibility.canSeeTarget(sender, target)) {
                plugin.getMessageManager().send(
                        sender,
                        Messages.PLAYER_NOT_FOUND,
                        "{player}", MessagingService.safeName(targetName)
                );
                return;
            }
        }

        moderation.setVanished(sender, target, desiredState);
    }

    @Nullable
    private static Boolean parseState(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on", "enable", "true" -> Boolean.TRUE;
            case "off", "disable", "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
