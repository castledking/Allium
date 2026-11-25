package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * {@code /pvp [on|off] [player]}: toggles or sets a player's persistent PvP preference.
 *
 * <p>Supports bare {@code /pvp}, explicit {@code /pvp on|off}, and staff targeting
 * ({@code /pvp <player>} or {@code /pvp on|off <player>}) with {@code allium.pvp.other}
 * (or {@code allium.pvp.others}). Persists through {@link PreferencesModule}.
 */
public final class PvpCommand implements SpecCommandHandler {

    private static final String OTHERS_PERMISSION_ALIAS = "allium.pvp.others";

    private final PluginStart plugin;

    public PvpCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.PVP;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (!(plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule preferences)
                || !preferences.isEnabled()) {
            plugin.getMessageManager().send(sender, Messages.PVP_DISABLED);
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
            if (!canTargetOthers(sender)) {
                plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "pvp on others");
                return;
            }
            target = findVisiblePlayer(sender, targetName);
            if (target == null) {
                plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", targetName);
                return;
            }
        }

        EffectivePreferences current = preferences.effective(target.getUniqueId()).orElse(null);
        if (current == null) {
            plugin.getMessageManager().send(sender, Messages.PREFERENCES_UNAVAILABLE, "{player}", target.getName());
            return;
        }

        boolean enabled = state != null ? state : !current.pvpEnabled();
        if (enabled != current.pvpEnabled()) {
            preferences.sessions().change(target.getUniqueId(), PreferenceChange.set(PreferenceKey.PVP_ENABLED, enabled));
        }

        if (target.equals(sender)) {
            plugin.getMessageManager().send(sender, enabled ? Messages.PVP_ON : Messages.PVP_OFF);
        } else {
            plugin.getMessageManager().send(sender, enabled ? Messages.PVP_OTHER_ON : Messages.PVP_OTHER_OFF,
                    "{player}", target.getName());
            UtilitySupport.send(plugin, target, enabled ? Messages.PVP_ON : Messages.PVP_OFF);
        }
    }

    private boolean canTargetOthers(CommandSender sender) {
        return sender.hasPermission(spec().othersPermission()) || sender.hasPermission(OTHERS_PERMISSION_ALIAS);
    }

    @Nullable
    private Player findVisiblePlayer(CommandSender viewer, String query) {
        Player exact = plugin.getServer().getPlayerExact(query);
        if (exact != null && PlayerNameVisibility.canSee(viewer, exact)) {
            return exact;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        Player prefixMatch = null;
        for (Player candidate : plugin.getServer().getOnlinePlayers()) {
            if (!PlayerNameVisibility.canSee(viewer, candidate)) {
                continue;
            }
            String name = candidate.getName().toLowerCase(Locale.ROOT);
            if (name.equals(lower)) {
                return candidate;
            }
            if (prefixMatch == null && name.startsWith(lower)) {
                prefixMatch = candidate;
            }
        }
        return prefixMatch;
    }

    @Nullable
    private static Boolean parseState(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "true" -> Boolean.TRUE;
            case "off", "disable", "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
