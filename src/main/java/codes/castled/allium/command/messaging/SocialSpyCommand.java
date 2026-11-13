package codes.castled.allium.command.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceKey;
import org.bukkit.entity.Player;

/** Self-only, persisted opt-in. Permission is also checked at every observed delivery. */
public final class SocialSpyCommand implements SpecCommandHandler {
    private final PluginStart plugin;

    public SocialSpyCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SOCIALSPY;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (!(context.sender() instanceof Player player) || args.length > 1
                || args.length == 1 && !args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off")) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        if (!(plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule preferences) || !preferences.isEnabled()) {
            plugin.getMessageManager().send(player, Messages.SOCIALSPY_DISABLED);
            return;
        }
        EffectivePreferences current = preferences.effective(player.getUniqueId()).orElse(null);
        if (current == null) {
            plugin.getMessageManager().send(player, Messages.PREFERENCES_UNAVAILABLE, "{player}", player.getName());
            return;
        }
        boolean enabled = args.length == 0 ? !current.socialSpy() : args[0].equalsIgnoreCase("on");
        if (enabled != current.socialSpy()) {
            // Visible immediately; a failed patch is rolled back by the existing preference store.
            preferences.sessions().change(player.getUniqueId(), PreferenceChange.set(PreferenceKey.SOCIAL_SPY, enabled))
                    .whenComplete((row, error) -> {
                        if (error != null) {
                            plugin.getScheduler().runOnServer(() -> {
                                if (player.isOnline()) {
                                    plugin.getMessageManager().send(player, Messages.SOCIALSPY_SAVE_FAILED);
                                }
                            });
                        }
                    });
        }
        plugin.getMessageManager().send(player, enabled ? Messages.SOCIALSPY_ON : Messages.SOCIALSPY_OFF);
    }
}
