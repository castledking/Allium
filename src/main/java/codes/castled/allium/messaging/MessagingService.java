package codes.castled.allium.messaging;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageSoundSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.message.SoundSettings;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.text.FormattedText;
import codes.castled.allium.text.TextSender;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Private messages, for {@code /msg} and {@code /reply}. The policy is evaluated here, at
 * delivery, not in the command executors: a reply re-checks everything.
 *
 * <ul>
 *   <li>Toggled-off and ignoring recipients get the sender one generic message ("can't receive
 *       messages right now"); a vanished recipient the sender may not see looks offline.</li>
 *   <li>A blocked send produces nothing for the recipient: no message, no sound.</li>
 *   <li>The body is the sender's untrusted text ({@link UntrustedText}); the surrounding format
 *       comes from the lang file.</li>
 *   <li>The recipient hears the received message's sound at their message sound volume.</li>
 * </ul>
 */
public final class MessagingService implements Listener {

    private final PluginStart plugin;
    private final IgnoreService ignores;
    private final ReplyTargets replies = new ReplyTargets();
    private volatile MuteSource mutes = MuteSource.NONE;
    private volatile VanishSource vanish = VanishSource.METADATA;

    public MessagingService(PluginStart plugin, IgnoreService ignores) {
        this.plugin = plugin;
        this.ignores = ignores;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        HandlerList.unregisterAll(this);
    }

    /** Moderation plugs its mutes in here (no one is muted by default). */
    public void setMuteSource(MuteSource source) {
        mutes = source == null ? MuteSource.NONE : source;
    }

    /** Allium's own vanish plugs in here (the default reads the common {@code vanished} metadata). */
    public void setVanishSource(VanishSource source) {
        vanish = source == null ? VanishSource.METADATA : source;
    }

    public VanishSource vanish() {
        return vanish;
    }

    static UUID idOf(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : ReplyTargets.CONSOLE;
    }

    /** Whether {@code viewer} may see {@code target} (vanish and hidePlayer, unless allium.vanish.see). */
    public boolean visibleTo(CommandSender viewer, Player target) {
        if (!(viewer instanceof Player player) || viewer.hasPermission(MessagePolicy.SEE_VANISHED)) {
            return true;
        }
        return !vanish.hiddenFrom(player, target);
    }

    /**
     * The online player called {@code name} as {@code viewer} sees them: an exact name first, then a
     * unique name prefix among players visible to the viewer. A hidden player matches only by exact
     * name, so a vanished player still gets NOT_FOUND from the policy, like an offline one.
     */
    @Nullable
    public Player find(CommandSender viewer, String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        String prefix = name.toLowerCase(Locale.ROOT);
        Player match = null;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix) && visibleTo(viewer, online)) {
                if (match != null) {
                    return null;
                }
                match = online;
            }
        }
        return match;
    }

    /**
     * Sends {@code body} from {@code sender} to {@code recipient} ({@code null} when no such player
     * is online), naming them {@code typedName} in any refusal.
     */
    public MessagePolicy.Outcome send(CommandSender sender, @Nullable CommandSender recipient, String typedName, String body) {
        typedName = safeName(typedName);
        MessagePolicy.Sender from = new MessagePolicy.Sender(sender instanceof Player p ? p.getUniqueId() : null, sender::hasPermission,
                sender instanceof Player p && mutes.isMuted(p.getUniqueId()));
        MessagePolicy.Recipient to = recipientOf(sender, recipient);
        MessagePolicy.Outcome outcome = MessagePolicy.decide(from, to);
        String shownName = recipient instanceof Player target ? target.getName() : recipient instanceof ConsoleCommandSender ? consoleName() : typedName;
        switch (outcome) {
            case DELIVER -> deliver(sender, recipient, body);
            case SENDER_MUTED -> plugin.getMessageManager().send(sender, Messages.MSG_MUTED);
            case NOT_FOUND -> plugin.getMessageManager().send(sender, Messages.PLAYER_NOT_FOUND, "{player}", typedName);
            case SELF -> plugin.getMessageManager().send(sender, Messages.MSG_SELF);
            case CANNOT_RECEIVE -> plugin.getMessageManager().send(sender, Messages.MSG_CANNOT_RECEIVE, "{player}", shownName);
            case UNAVAILABLE -> plugin.getMessageManager().send(sender, Messages.PREFERENCES_UNAVAILABLE, "{player}", shownName);
        }
        return outcome;
    }

    /** {@code /reply}: to the last partner, re-checking the whole policy now. */
    public void reply(CommandSender sender, String body) {
        Optional<UUID> target = replies.target(idOf(sender));
        if (target.isEmpty()) {
            plugin.getMessageManager().send(sender, Messages.MSG_NO_REPLY_TARGET);
            return;
        }
        CommandSender recipient = target.get().equals(ReplyTargets.CONSOLE) ? Bukkit.getConsoleSender() : Bukkit.getPlayer(target.get());
        if (recipient == null) {
            replies.forget(target.get());
            plugin.getMessageManager().send(sender, Messages.MSG_NO_REPLY_TARGET);
            return;
        }
        send(sender, recipient, recipient instanceof Player p ? p.getName() : consoleName(), body);
    }

    private MessagePolicy.Recipient recipientOf(CommandSender sender, @Nullable CommandSender recipient) {
        if (recipient == null) {
            return new MessagePolicy.Recipient(null, false, false, MessagePolicy.Acceptance.ACCEPTS, MessagePolicy.Ignore.NO);
        }
        if (!(recipient instanceof Player target)) {
            return new MessagePolicy.Recipient(ReplyTargets.CONSOLE, true, false, MessagePolicy.Acceptance.ACCEPTS, MessagePolicy.Ignore.NO);
        }
        boolean hidden = sender instanceof Player viewer && vanish.hiddenFrom(viewer, target);
        MessagePolicy.Ignore ignore = sender instanceof Player p ? ignores.ignores(target.getUniqueId(), p.getUniqueId()) : MessagePolicy.Ignore.NO;
        return new MessagePolicy.Recipient(target.getUniqueId(), target.isOnline(), hidden, acceptance(target.getUniqueId()), ignore);
    }

    private MessagePolicy.Acceptance acceptance(UUID player) {
        PreferencesModule preferences = preferences();
        if (preferences == null) {
            return MessagePolicy.Acceptance.ACCEPTS; // no preferences module: nobody can toggle messages off
        }
        return switch (preferences.acceptance(player, PreferenceKey.ACCEPT_MESSAGES)) {
            case ACCEPTS -> MessagePolicy.Acceptance.ACCEPTS;
            case REFUSES -> MessagePolicy.Acceptance.REFUSES;
            case UNAVAILABLE -> MessagePolicy.Acceptance.UNAVAILABLE;
        };
    }

    @Nullable
    PreferencesModule preferences() {
        return plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule module && module.isEnabled() ? module : null;
    }

    /** A typed name is untrusted too: keep only what a Minecraft name can contain before it goes into a template. */
    public static String safeName(String typed) {
        String kept = typed.replaceAll("[^A-Za-z0-9_]", "");
        return kept.isEmpty() ? "?" : kept.substring(0, Math.min(kept.length(), 16));
    }

    private String consoleName() {
        return plugin.getMessageManager().resolve(Messages.MSG_CONSOLE_NAME);
    }

    private String nameOf(CommandSender who) {
        return who instanceof Player p ? p.getName() : consoleName();
    }

    private void deliver(CommandSender sender, CommandSender recipient, String body) {
        FormattedText text = UntrustedText.parse(body, UntrustedText.Allow.of(sender::hasPermission));
        String sent = plugin.getMessageManager().resolve(Messages.MSG_SENT, "{recipient}", nameOf(recipient));
        String received = plugin.getMessageManager().resolve(Messages.MSG_RECEIVED, "{sender}", nameOf(sender));
        // The literal path: on Spigot, a legacy string would turn URLs in the body into clickable links.
        TextSender.sendLiteral(sender, UntrustedText.insert(sent, text));
        TextSender.sendLiteral(recipient, UntrustedText.insert(received, text));
        if (sender instanceof Player self) {
            // The sender's own confirmation sound, when the lang entry has one. By default it follows
            // the sender's /mfx volume too (sounds.messages.scale-sent-sound), so muting silences both.
            MessageSoundSpec sentSound = plugin.getLanguageManager().getSound(Messages.MSG_SENT);
            if (sentSound != null) {
                if (plugin.getConfig().getBoolean("sounds.messages.scale-sent-sound", true)) {
                    double volume = senderVolume(self.getUniqueId());
                    if (volume > 0.0D) {
                        plugin.getMessageManager().playNotificationSound(self, sentSound, volume);
                    }
                } else {
                    plugin.getMessageManager().playSound(self, sentSound);
                }
            }
        }
        if (recipient instanceof Player target) {
            MessageSoundSpec sound = plugin.getLanguageManager().getSound(Messages.MSG_RECEIVED);
            if (sound != null) {
                plugin.getMessageManager().playNotificationSound(target, sound, recipientVolume(target.getUniqueId()));
            }
        }
        replies.delivered(idOf(sender), idOf(recipient));
    }

    /**
     * The sender's own message sound volume. While their preferences are not loaded it is 0, so a
     * player who muted is never surprised by a sound before their setting arrives.
     */
    private double senderVolume(UUID player) {
        PreferencesModule preferences = preferences();
        if (preferences == null) {
            return SoundSettings.read(plugin.getConfig(), message -> { }).messageFallbackVolume();
        }
        return preferences.effective(player).map(EffectivePreferences::messageSoundVolume).orElse(0.0D);
    }

    private double recipientVolume(UUID player) {
        PreferencesModule preferences = preferences();
        EffectivePreferences effective = preferences == null ? null : preferences.effective(player).orElse(null);
        return effective != null ? effective.messageSoundVolume()
                : SoundSettings.read(plugin.getConfig(), message -> { }).messageFallbackVolume();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        replies.forget(event.getPlayer().getUniqueId());
    }
}
