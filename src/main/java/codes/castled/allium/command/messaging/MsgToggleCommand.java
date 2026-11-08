package codes.castled.allium.command.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * {@code /msgtoggle [on|off] [player]}, per the roadmap contract: on = accept, off = reject. A bare
 * toggle flips the setting; {@code on} and {@code off} are idempotent. Another player needs
 * {@code allium.msgtoggle.others}; the console must name a target; invalid syntax never toggles the
 * sender. The setting persists through the preference store.
 */
public final class MsgToggleCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final MessagingService messaging;

    public MsgToggleCommand(PluginStart plugin, MessagingService messaging) {
        this.plugin = plugin;
        this.messaging = messaging;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.MSGTOGGLE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (!(plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule preferences) || !preferences.isEnabled()) {
            plugin.getMessageManager().send(sender, Messages.MSGTOGGLE_DISABLED);
            return;
        }
        Boolean state = null;
        String targetName = null;
        if (args.length == 1) {
            state = parseState(args[0]);
            if (state == null) {
                targetName = args[0];
            }
        } else if (args.length == 2) {
            state = parseState(args[0]);
            if (state == null) {
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
            if (!sender.hasPermission(spec().othersPermission())) {
                plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "msgtoggle for others");
                return;
            }
            target = messaging.find(sender, targetName);
            if (target == null || !messaging.visibleTo(sender, target)) {
                plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", MessagingService.safeName(targetName));
                return;
            }
        }
        EffectivePreferences current = preferences.effective(target.getUniqueId()).orElse(null);
        if (current == null) {
            plugin.getMessageManager().send(sender, Messages.PREFERENCES_UNAVAILABLE, "{player}", target.getName());
            return;
        }
        boolean accept = state != null ? state : !current.acceptMessages();
        if (accept != current.acceptMessages()) {
            preferences.sessions().change(target.getUniqueId(), PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES, accept));
        }
        if (target.equals(sender)) {
            plugin.getMessageManager().send(sender, accept ? Messages.MSGTOGGLE_ON : Messages.MSGTOGGLE_OFF);
        } else {
            plugin.getMessageManager().send(sender, accept ? Messages.MSGTOGGLE_OTHER_ON : Messages.MSGTOGGLE_OTHER_OFF, "{player}", target.getName());
            plugin.getMessageManager().send(target, accept ? Messages.MSGTOGGLE_ON : Messages.MSGTOGGLE_OFF);
        }
    }

    @Nullable
    private static Boolean parseState(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on" -> Boolean.TRUE;
            case "off" -> Boolean.FALSE;
            default -> null;
        };
    }
}
