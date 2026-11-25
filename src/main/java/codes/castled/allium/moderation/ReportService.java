package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.discord.DiscordFeeds;
import codes.castled.allium.discord.StaffFeeds;
import codes.castled.allium.discord.StaffPosts;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.messaging.UntrustedText;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.text.FormattedText;
import codes.castled.allium.text.TextSender;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/**
 * {@code /report} and {@code /escalate}. A report or escalation goes to the staff online who may
 * receive it, to its Discord feed, to the console and to the moderation audit log.
 *
 * <p>{@code /report <player> <what happened>} reports a player; {@code /report <message>} (when its
 * first word is no known player) reports anything else. Players may report once per
 * {@code moderation.reports.cooldown-seconds}. {@code /escalate <target> <reason>} is for staff
 * handing an issue up; the console's {@code /allium escalate <staff> <target> <reason>} is
 * pre-recode Allium's {@code /core escalate}.
 */
public final class ReportService {

    public static final String PERM_REPORT = "allium.report";
    public static final String PERM_RECEIVE = "allium.report.receive";
    public static final String PERM_COOLDOWN_BYPASS = "allium.report.cooldown.bypass";
    public static final String PERM_ESCALATE = "allium.escalate";
    public static final String PERM_ESCALATE_RECEIVE = "allium.escalate.receive";
    public static final String PERM_HELPOP = "allium.helpop";
    public static final String PERM_HELPOP_RECEIVE = "allium.helpop.receive";
    public static final String PERM_HELPOP_COOLDOWN_BYPASS = "allium.helpop.cooldown.bypass";
    public static final int MAX_LENGTH = 256;

    private final PluginStart plugin;
    private final ModerationRepository repository;
    private final StaffFeeds feeds;
    private final Executor io;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHelpop = new ConcurrentHashMap<>();
    private volatile long cooldownMillis;

    public ReportService(PluginStart plugin, ModerationRepository repository, StaffFeeds feeds, Executor io, LongSupplier clock) {
        this.plugin = plugin;
        this.repository = repository;
        this.feeds = feeds;
        this.io = io;
        this.clock = clock;
        reloadSettings();
    }

    public void reloadSettings() {
        long seconds = plugin.getConfig().getLong("moderation.reports.cooldown-seconds", 60L);
        if (seconds < 0 || seconds > 86_400) {
            plugin.logger().warning("moderation.reports.cooldown-seconds must be 0 to 86400; using 60.");
            seconds = 60L;
        }
        cooldownMillis = seconds * 1000L;
    }

    /** What a report's words mean once its first word is (or is not) a known player. */
    record Parsed(@Nullable String player, @Nullable UUID playerId, String text) {
    }

    /** A player report needs words after the name; anything else is a report as typed. Null when it is empty. */
    @Nullable
    static Parsed parse(String[] args, @Nullable NameCache.Entry first) {
        if (args.length == 0) return null;
        if (first != null) {
            if (args.length < 2) return null;
            String text = clean(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            return text.isEmpty() ? null : new Parsed(first.name(), first.uuid(), text);
        }
        String text = clean(String.join(" ", args));
        return text.isEmpty() ? null : new Parsed(null, null, text);
    }

    static String clean(String raw) {
        String text = UntrustedText.clean(raw).trim().replaceAll("\\s+", " ");
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH);
    }

    // -- /report ---------------------------------------------------------------------------------

    public void report(CommandSender sender, String[] args, Runnable usage) {
        if (args.length == 0) {
            usage.run();
            return;
        }
        Player reporter = sender instanceof Player player ? player : null;
        if (reporter != null && !reporter.hasPermission(PERM_COOLDOWN_BYPASS)) {
            Long last = lastReport.get(reporter.getUniqueId());
            long wait = last == null ? 0L : last + cooldownMillis - clock.getAsLong();
            if (wait > 0L) {
                send(sender, Messages.REPORT_COOLDOWN, "{time}", DurationParser.formatRemaining(wait));
                return;
            }
        }
        if (plugin.getPlayerNames() == null) {
            deliver(sender, reporter, args, null, usage);
            return;
        }
        try {
            plugin.getPlayerNames().resolve(args[0]).whenComplete((found, error) -> BukkitServerThread.runFor(
                    plugin, plugin.getScheduler(), sender,
                    () -> deliver(sender, reporter, args, error == null && found != null ? found.orElse(null) : null, usage),
                    () -> {}, 0L));
        } catch (RejectedExecutionException busy) {
            deliver(sender, reporter, args, null, usage);
        }
    }

    private void deliver(CommandSender sender, @Nullable Player reporter, String[] args, @Nullable NameCache.Entry first, Runnable usage) {
        if (reporter != null && !reporter.isOnline()) return;
        Parsed parsed = parse(args, first);
        if (parsed == null) {
            usage.run();
            return;
        }
        if (reporter != null && reporter.getUniqueId().equals(parsed.playerId())) {
            send(sender, Messages.REPORT_SELF);
            return;
        }
        if (reporter != null) lastReport.put(reporter.getUniqueId(), clock.getAsLong());
        String name = reporter == null ? "Console" : reporter.getName();
        String where = reporter == null ? null : where(reporter.getLocation());
        for (Player staff : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (staff == reporter || !staff.hasPermission(PERM_RECEIVE)) continue;
            Messages alert = parsed.player() == null ? Messages.REPORT_STAFF_GENERAL : Messages.REPORT_STAFF;
            literal(staff, alert, parsed.text(), "{reporter}", name, "{player}", parsed.player() == null ? "" : parsed.player(),
                    "{where}", where == null ? "" : "(" + where + ")");
        }
        plugin.logger().info("[Report] " + name + (parsed.player() == null ? "" : " reported " + parsed.player()) + ": " + parsed.text()
                + (where == null ? "" : " (" + where + ")"));
        feeds.post(DiscordFeeds.Feed.REPORTS, StaffPosts.report(name, parsed.player(), parsed.text(), where, Instant.now()));
        audit("REPORT", name, parsed.playerId(), parsed.player(), parsed.text() + (where == null ? "" : " @ " + where));
        send(sender, Messages.REPORT_SENT);
    }

    // -- /helpop ---------------------------------------------------------------------------------

    /**
     * Sends a staff help request distinct from {@code /report}: every argument is preserved as
     * message text (never split on a target player name), recipients require
     * {@link #PERM_HELPOP_RECEIVE}, and audit entries record the {@code HELPOP} action.
     */
    public void helpop(CommandSender sender, String[] args, Runnable usage) {
        if (args.length == 0) {
            usage.run();
            return;
        }
        String text = clean(String.join(" ", args));
        if (text.isEmpty()) {
            usage.run();
            return;
        }
        Player requester = sender instanceof Player player ? player : null;
        if (requester != null
                && !requester.hasPermission(PERM_HELPOP_COOLDOWN_BYPASS)
                && !requester.hasPermission(PERM_COOLDOWN_BYPASS)) {
            Long last = lastHelpop.get(requester.getUniqueId());
            long wait = last == null ? 0L : last + cooldownMillis - clock.getAsLong();
            if (wait > 0L) {
                send(sender, Messages.HELPOP_COOLDOWN, "{time}", DurationParser.formatRemaining(wait));
                return;
            }
        }
        if (requester != null) lastHelpop.put(requester.getUniqueId(), clock.getAsLong());
        String name = requester == null ? "Console" : requester.getName();
        String where = requester == null ? null : where(requester.getLocation());
        for (Player staff : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (staff == requester || !staff.hasPermission(PERM_HELPOP_RECEIVE)) continue;
            literal(staff, Messages.HELPOP_STAFF, text, "{player}", name,
                    "{where}", where == null ? "" : "(" + where + ")");
        }
        plugin.logger().info("[HelpOp] " + name + ": " + text + (where == null ? "" : " (" + where + ")"));
        feeds.post(DiscordFeeds.Feed.REPORTS, StaffPosts.report(name, null, "[HelpOp] " + text, where, Instant.now()));
        audit("HELPOP", name, requester == null ? null : requester.getUniqueId(), requester == null ? null : name,
                text + (where == null ? "" : " @ " + where));
        send(sender, Messages.HELPOP_SENT);
    }

    // -- /escalate -------------------------------------------------------------------------------

    /**
     * Escalates {@code target}'s issue for {@code staff}; {@code sender} is told it was sent, and so is
     * {@code staff} when they are online and someone else (the console) sent it.
     */
    public void escalate(CommandSender sender, String staff, String target, String rawReason) {
        String staffName = MessagingService.safeName(staff);
        String targetName = MessagingService.safeName(target);
        String reason = UntrustedText.clean(rawReason).trim();
        if (reason.length() > 1000) reason = reason.substring(0, 1000);
        String chat = reason.replace("\\n", " ").replaceAll("\\s+", " ");
        for (Player online : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (!online.hasPermission(PERM_ESCALATE_RECEIVE)) continue;
            literal(online, Messages.ESCALATE_STAFF, chat, "{staff}", staffName, "{target}", targetName);
        }
        plugin.logger().info("[Escalation] " + staffName + " escalated " + targetName + ": " + chat);
        // Pre-recode Allium let a literal \n break lines in Discord.
        feeds.post(DiscordFeeds.Feed.ESCALATIONS, StaffPosts.escalation(staffName, targetName, reason.replace("\\n", "\n"), Instant.now()));
        Optional<UUID> targetId = Optional.ofNullable(plugin.getServer().getPlayerExact(targetName)).map(Player::getUniqueId);
        audit("ESCALATE", staffName, targetId.orElse(null), targetName, chat);
        send(sender, Messages.ESCALATE_SENT);
        Player named = plugin.getServer().getPlayerExact(staffName);
        if (named != null && named != sender) send(named, Messages.ESCALATE_SENT);
    }

    // -- Helpers ---------------------------------------------------------------------------------

    private void audit(String action, String actor, @Nullable UUID target, @Nullable String targetName, String detail) {
        try {
            io.execute(() -> {
                try {
                    repository.recordAudit(action, actor, target, targetName, detail);
                } catch (SQLException failed) {
                    plugin.logger().warning("Could not record a " + action.toLowerCase(java.util.Locale.ROOT) + " in the moderation log: "
                            + failed.getMessage());
                }
            });
        } catch (RejectedExecutionException busy) {
            plugin.logger().warning("The moderation log is busy; a " + action.toLowerCase(java.util.Locale.ROOT) + " was not recorded.");
        }
    }

    private static String where(Location at) {
        return (at.getWorld() == null ? "?" : at.getWorld().getName()) + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ();
    }

    private void send(CommandSender sender, Messages message, String... replacements) {
        UtilitySupport.send(plugin, sender, message, replacements);
    }

    private void literal(CommandSender recipient, Messages message, String body, String... replacements) {
        FormattedText formatted = UntrustedText.insert(plugin.getMessageManager().resolve(message, replacements),
                UntrustedText.parse(body, UntrustedText.Allow.NONE));
        UtilitySupport.onOwner(plugin, recipient, () -> {
            TextSender.sendLiteral(recipient, formatted);
            if (recipient instanceof Player player) plugin.getMessageManager().playSoundIfPresent(player, message);
        });
    }
}
