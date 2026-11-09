package codes.castled.allium.command.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.MessageSoundRules;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceDefaults;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.preference.StoredPreferences;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * {@code /messagesounds} ({@code /msounds}, {@code /mfx}): the player's own private-message
 * sound volume, per the roadmap contract.
 * <ul>
 *   <li>No arguments: the current volume, where it comes from, and usage.</li>
 *   <li>{@code volume <0-1>}: a finite override; anything else changes nothing.</li>
 *   <li>{@code toggle}: an audible volume goes to 0; from 0 it restores the last positive override,
 *       or the server default (saying so when that is 0 too).</li>
 *   <li>{@code reset}: removes the override and the restoration state, so the player follows
 *       {@code sounds.messages.fallback-volume}, now and after later config changes.</li>
 * </ul>
 * The rules live in {@link MessageSoundRules}; the state persists through the preference store
 * and fails closed while a player's preferences load.
 */
public final class MessageSoundsCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public MessageSoundsCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.MESSAGESOUNDS;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().send(sender, Messages.PLAYER_ONLY);
            return;
        }
        if (!(plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule preferences) || !preferences.isEnabled()) {
            plugin.getMessageManager().send(sender, Messages.SOUNDS_DISABLED);
            return;
        }
        PreferenceSessions.View view = preferences.sessions().view(player.getUniqueId()).orElse(null);
        StoredPreferences stored = view == null ? null : view.stored();
        if (stored == null || view.effective() == null) {
            plugin.getMessageManager().send(sender, Messages.PREFERENCES_UNAVAILABLE, "{player}", player.getName());
            return;
        }
        PreferenceDefaults defaults = preferences.defaults();
        String[] args = context.args();
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "" -> status(player, stored, view.effective().messageSoundVolume(), defaults);
            case "volume" -> {
                if (args.length != 2) {
                    context.sendInvalidArgs("/mfx volume <0-1>");
                    return;
                }
                OptionalDouble volume = MessageSoundRules.parseVolume(args[1]);
                if (volume.isEmpty()) {
                    plugin.getMessageManager().send(sender, Messages.SOUNDS_VOLUME_INVALID, "{input}", shown(args[1]));
                    return;
                }
                change(player, MessageSoundRules.setVolume(stored, volume.getAsDouble()));
                if (volume.getAsDouble() == 0.0D) {
                    plugin.getMessageManager().send(sender, Messages.SOUNDS_MUTED);
                } else {
                    plugin.getMessageManager().send(sender, Messages.SOUNDS_VOLUME_SET, "{volume}", format(volume.getAsDouble()));
                }
            }
            case "toggle" -> {
                if (args.length != 1) {
                    context.sendInvalidArgs("/mfx toggle");
                    return;
                }
                MessageSoundRules.Toggle toggle = MessageSoundRules.toggle(stored, defaults);
                change(player, toggle.change());
                switch (toggle.outcome()) {
                    case MUTED -> plugin.getMessageManager().send(sender, Messages.SOUNDS_MUTED);
                    case RESTORED -> plugin.getMessageManager().send(sender, Messages.SOUNDS_RESTORED, "{volume}", format(toggle.volume()));
                    case FALLBACK -> plugin.getMessageManager().send(sender, Messages.SOUNDS_FALLBACK, "{volume}", format(toggle.volume()));
                    case FALLBACK_SILENT -> plugin.getMessageManager().send(sender, Messages.SOUNDS_FALLBACK_SILENT);
                }
            }
            case "reset" -> {
                if (args.length != 1) {
                    context.sendInvalidArgs("/mfx reset");
                    return;
                }
                change(player, MessageSoundRules.reset());
                plugin.getMessageManager().send(sender, Messages.SOUNDS_RESET, "{volume}", format(defaults.messageSoundVolume()));
            }
            default -> {
                plugin.getMessageManager().send(sender, Messages.SOUNDS_USAGE);
            }
        }
    }

    private void status(Player player, StoredPreferences stored, double effective, PreferenceDefaults defaults) {
        Double override = stored.messageSoundVolume();
        Double restore = stored.messageSoundRestore();
        if (effective <= 0.0D && override != null) {
            // Muted by the player: say what a toggle brings back (their old override, else the default).
            double back = restore != null && restore > 0.0D ? restore : defaults.messageSoundVolume();
            plugin.getMessageManager().send(player, Messages.SOUNDS_STATUS_MUTED, "{volume}", format(back));
        } else if (override != null) {
            plugin.getMessageManager().send(player, Messages.SOUNDS_STATUS_OVERRIDE, "{volume}", format(effective));
        } else {
            plugin.getMessageManager().send(player, Messages.SOUNDS_STATUS_DEFAULT, "{volume}", format(effective));
        }
        plugin.getMessageManager().send(player, Messages.SOUNDS_USAGE);
    }

    private void change(Player player, PreferenceChange change) {
        if (plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule preferences) {
            // The live session sees the patch at once; the store persists it on its own lane.
            preferences.sessions().change(player.getUniqueId(), change);
        }
    }

    /** Up to three decimals, without trailing zeros: 0.5, 0.35, 1. */
    static String format(double volume) {
        return BigDecimal.valueOf(volume).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** What the player typed, reduced to number-like characters so it cannot carry formatting. */
    static String shown(String raw) {
        String kept = raw.replaceAll("[^A-Za-z0-9.+\\-]", "");
        return kept.length() > 16 ? kept.substring(0, 16) + "…" : kept;
    }
}
