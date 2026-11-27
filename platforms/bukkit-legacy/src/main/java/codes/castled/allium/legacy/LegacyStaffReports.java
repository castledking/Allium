package codes.castled.allium.legacy;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

final class LegacyStaffReports {
    private static final long MAX_COOLDOWN_MILLIS = 86_400_000L;
    private static final String REPORT_BYPASS = "allium.report.cooldown.bypass";

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyMessageManager messages;
    private final LegacyPlayerInfoModule playerInfo;
    private final BiPredicate<CommandSender, Player> canSee;
    private final Map<UUID, Long> lastReport = new HashMap<>();
    private final Map<UUID, Long> lastHelp = new HashMap<>();
    private long cooldownMillis;

    LegacyStaffReports(JavaPlugin plugin, LegacyConfigManager config, LegacyMessageManager messages,
                       LegacyPlayerInfoModule playerInfo, BiPredicate<CommandSender, Player> canSee) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.playerInfo = playerInfo;
        this.canSee = canSee;
    }

    void reload() {
        long seconds = config.raw().getLong("moderation.reports.cooldown-seconds", 60L);
        if (seconds < 0 || seconds > MAX_COOLDOWN_MILLIS / 1000L) {
            plugin.getLogger().warning("moderation.reports.cooldown-seconds must be 0 to 86400; using 60.");
            seconds = 60L;
        }
        cooldownMillis = seconds * 1000L;
    }

    boolean report(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.report")) return refuse(sender);
        if (args.length == 0) return false;
        Player online = plugin.getServer().getPlayerExact(args[0]);
        UUID target = online == null ? playerInfo.findKnownPlayerId(args[0])
                : canSee.test(sender, online) ? online.getUniqueId() : null;
        String body = clean(args, target == null ? 0 : 1, 256);
        if (body.isEmpty()) return false;
        if (sender instanceof Player && ((Player) sender).getUniqueId().equals(target)) {
            messages.send(sender, "report.self", "{prefix} &cYou can't report yourself.");
            return true;
        }
        long wait = record(lastReport, sender, sender.hasPermission(REPORT_BYPASS));
        if (wait > 0) {
            messages.send(sender, "report.cooldown", "{prefix} &cYou can report again in &f{time}&c.",
                    "{time}", remaining(wait));
            return true;
        }
        String actor = actor(sender);
        String where = where(sender);
        if (target == null) {
            alert(sender, "allium.report.receive", "report.staff-general", "&c[Report] &f{reporter}&7: &f{message} &8{where}",
                    body, "{reporter}", actor, "{where}", where);
            log("[Report] " + actor + ": " + body, where);
        } else {
            String name = safeName(online == null ? args[0] : online.getName());
            alert(sender, "allium.report.receive", "report.staff", "&c[Report] &f{reporter} &7reported &f{player}&7: &f{message} &8{where}",
                    body, "{reporter}", actor, "{player}", name, "{where}", where);
            log("[Report] " + actor + " reported " + name + ": " + body, where);
        }
        messages.send(sender, "report.sent", "{prefix} &aThanks; your report was sent to staff.");
        return true;
    }

    boolean help(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.helpop")) return refuse(sender);
        String body = clean(args, 0, 256);
        if (body.isEmpty()) return false;
        long wait = record(lastHelp, sender,
                sender.hasPermission(REPORT_BYPASS) || sender.hasPermission("allium.helpop.cooldown.bypass"));
        if (wait > 0) {
            messages.send(sender, "helpop.cooldown", "{prefix} &cYou can send another help request in &f{time}&c.",
                    "{time}", remaining(wait));
            return true;
        }
        String actor = actor(sender);
        String where = where(sender);
        alert(sender, "allium.helpop.receive", "helpop.staff", "&e[HelpOp] &f{player}&7: &f{message} &8{where}",
                body, "{player}", actor, "{where}", where);
        log("[HelpOp] " + actor + ": " + body, where);
        messages.send(sender, "helpop.sent", "{prefix} &aYour help request was sent to staff.");
        return true;
    }

    boolean escalate(CommandSender sender, String staff, String[] args, int targetIndex) {
        if (!sender.hasPermission("allium.escalate")) return refuse(sender);
        if (args.length <= targetIndex + 1) return false;
        String reason = clean(args, targetIndex + 1, 1000).replace("\\n", " ").replaceAll("\\s+", " ").trim();
        if (reason.isEmpty()) return false;
        String actor = safeName(staff);
        String target = safeName(args[targetIndex]);
        alert(null, "allium.escalate.receive", "report.escalate-staff", "&4[Escalation] &f{staff} &7escalated &f{target}&7: &f{message}",
                reason, "{staff}", actor, "{target}", target);
        plugin.getLogger().info("[Escalation] " + actor + " escalated " + target + ": " + reason);
        sentEscalation(sender);
        if (targetIndex > 0) {
            Player namedStaff = plugin.getServer().getPlayerExact(actor);
            if (namedStaff != null) sentEscalation(namedStaff);
        }
        return true;
    }

    List<String> complete(CommandSender sender, String command, String prefix) {
        if (!sender.hasPermission("allium." + command)) return Collections.emptyList();
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> player != sender && canSee.test(sender, player)).map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
    }

    private void sentEscalation(CommandSender sender) {
        messages.send(sender, "report.escalate-sent", "{prefix} &aEscalation sent to staff.");
    }

    private void log(String message, String where) {
        plugin.getLogger().info(where.isEmpty() ? message : message + " " + where);
    }

    private void alert(CommandSender excluded, String permission, String key, String fallback, String body, String... replacements) {
        for (Player recipient : plugin.getServer().getOnlinePlayers()) {
            if (recipient != excluded && recipient.hasPermission(permission)) {
                messages.sendWithLiteralBody(recipient, key, fallback, body, replacements);
            }
        }
    }

    private long record(Map<UUID, Long> requests, CommandSender sender, boolean bypass) {
        if (!(sender instanceof Player)) return 0;
        long now = System.currentTimeMillis();
        UUID actor = ((Player) sender).getUniqueId();
        Long previous = requests.get(actor);
        long wait = previous == null ? 0 : previous + cooldownMillis - now;
        if (!bypass && wait > 0) return wait;
        requests.values().removeIf(sent -> now - sent >= MAX_COOLDOWN_MILLIS);
        requests.put(actor, now);
        return 0;
    }

    private static String remaining(long wait) {
        return LegacyCompat.formatDuration(((wait + 999) / 1000) * 1000);
    }

    static String actor(CommandSender sender) {
        return sender instanceof Player ? safeName(sender.getName()) : "Console";
    }

    private static String safeName(String typed) {
        String name = typed.replaceAll("[^A-Za-z0-9_.]", "");
        return name.isEmpty() ? "?" : name.substring(0, Math.min(name.length(), 16));
    }

    private static String where(CommandSender sender) {
        if (!(sender instanceof Player)) return "";
        Location at = ((Player) sender).getLocation();
        if (at == null) return "";
        String world = at.getWorld() == null ? "?" : at.getWorld().getName().replaceAll("[^A-Za-z0-9_.-]", "_");
        return "(" + world + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ")";
    }

    private static String clean(String[] args, int start, int max) {
        String raw = String.join(" ", Arrays.copyOfRange(args, start, args.length));
        String text = raw.replaceAll("[\\p{Cc}§]", "").trim().replaceAll("\\s+", " ");
        if (text.length() <= max) return text;
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }

    private boolean refuse(CommandSender sender) {
        messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
        return true;
    }
}
