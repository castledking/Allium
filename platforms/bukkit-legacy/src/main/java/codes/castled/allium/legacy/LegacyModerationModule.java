package codes.castled.allium.legacy;

import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Java 8 / 1.12.2-1.19.4 moderation module:
 * {@code /vanish}, {@code /mute}, {@code /unmute}, {@code /mutes}, {@code /kick}, {@code /kickall},
 * {@code /ban}, {@code /tempban}, {@code /unban}, {@code /banlist}, {@code /ipban}, {@code /tempbanip},
 * {@code /unbanip}, {@code /freeze}, {@code /setjail}, {@code /deljail}, {@code /jails}, {@code /jail},
 * {@code /unjail}, {@code /togglejail}, {@code /jailedplayers}, {@code /warn}, {@code /notes},
 * {@code /unnote}, {@code /invsee}, {@code /helpop}, {@code /report}, {@code /socialspy},
 * {@code /commandspy}, {@code /spy}.
 */
final class LegacyModerationModule implements Listener {

    static final class MuteEntry {
        final String name;
        final long expiresAt; // 0 = permanent
        final String reason;

        MuteEntry(String name, long expiresAt, String reason) {
            this.name = name;
            this.expiresAt = expiresAt;
            this.reason = reason == null || reason.isEmpty() ? "Muted by staff." : reason;
        }

        boolean isExpired(long now) {
            return expiresAt > 0L && now >= expiresAt;
        }
    }

    static final class JailRecord {
        final String name;
        final String jailName;
        final long expiresAt; // 0 = indefinite
        final Location returnLocation;

        JailRecord(String name, String jailName, long expiresAt, Location returnLocation) {
            this.name = name;
            this.jailName = jailName;
            this.expiresAt = expiresAt;
            this.returnLocation = returnLocation;
        }

        boolean isExpired(long now) {
            return expiresAt > 0L && now >= expiresAt;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final Set<UUID> vanished = new HashSet<UUID>();
    private final Set<UUID> frozen = new HashSet<UUID>();
    private final Set<UUID> socialSpy = new HashSet<UUID>();
    private final Set<UUID> commandSpy = new HashSet<UUID>();
    private final Map<UUID, MuteEntry> mutes = new HashMap<UUID, MuteEntry>();
    private final Map<String, Location> jails = new LinkedHashMap<String, Location>();
    private final Map<UUID, JailRecord> jailed = new HashMap<UUID, JailRecord>();
    private final Map<UUID, List<String>> notes = new HashMap<UUID, List<String>>();

    LegacyModerationModule(JavaPlugin plugin, LegacyMessageManager messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    private void send(CommandSender recipient, String message) {
        messages.sendPrefixed(recipient, message);
    }

    void enable() {
        loadModeration();
    }

    void reload() {
        loadModeration();
    }

    void disable() {
        saveModeration();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            for (UUID hiddenId : vanished) {
                Player hidden = plugin.getServer().getPlayer(hiddenId);
                if (hidden != null && !hidden.equals(online)) {
                    LegacyCompat.showPlayer(plugin, online, hidden);
                }
            }
        }
        vanished.clear();
        frozen.clear();
        socialSpy.clear();
        commandSpy.clear();
    }

    boolean isVanished(UUID id) {
        return vanished.contains(id);
    }

    boolean isMuted(UUID id) {
        MuteEntry entry = mutes.get(id);
        if (entry == null) return false;
        if (entry.isExpired(System.currentTimeMillis())) {
            mutes.remove(id);
            saveModeration();
            return false;
        }
        return true;
    }

    void notifySocialSpy(CommandSender from, CommandSender to, String message) {
        String formatted = ChatColor.DARK_GRAY + "[SocialSpy] " + ChatColor.GRAY + from.getName()
                + " -> " + to.getName() + ": " + ChatColor.WHITE + message;
        for (UUID spyId : socialSpy) {
            Player spy = plugin.getServer().getPlayer(spyId);
            if (spy == null || spy.equals(from) || spy.equals(to)) continue;
            if (spy.hasPermission("allium.socialspy")) {
                spy.sendMessage(formatted);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        MuteEntry entry = mutes.get(p.getUniqueId());
        if (entry != null) {
            long now = System.currentTimeMillis();
            if (entry.isExpired(now)) {
                mutes.remove(p.getUniqueId());
            } else {
                event.setCancelled(true);
                String remaining = entry.expiresAt > 0L
                        ? " (" + LegacyCompat.formatDuration(entry.expiresAt - now) + " remaining)"
                        : " (permanent)";
                send(p, ChatColor.RED + "You are muted" + remaining + ": " + entry.reason);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        for (UUID hiddenId : vanished) {
            Player hidden = plugin.getServer().getPlayer(hiddenId);
            if (hidden != null && !hidden.equals(joining) && !joining.hasPermission("allium.vanish.see")) {
                LegacyCompat.hidePlayer(plugin, joining, hidden);
            }
        }
        JailRecord record = jailed.get(joining.getUniqueId());
        if (record != null) {
            if (record.isExpired(System.currentTimeMillis())) {
                releaseFromJail(joining.getUniqueId(), joining);
            } else {
                Location jailLoc = jails.get(record.jailName);
                if (jailLoc != null) {
                    joining.teleport(jailLoc);
                    send(joining, ChatColor.RED + "You are jailed in '" + record.jailName + "'.");
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        vanished.remove(id);
        socialSpy.remove(id);
        commandSpy.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (event.getTo() == null || event.getFrom() == null) return;
        if (frozen.contains(p.getUniqueId())) {
            if (event.getFrom().getX() != event.getTo().getX()
                    || event.getFrom().getY() != event.getTo().getY()
                    || event.getFrom().getZ() != event.getTo().getZ()) {
                Location stayed = event.getFrom().clone();
                stayed.setYaw(event.getTo().getYaw());
                stayed.setPitch(event.getTo().getPitch());
                event.setTo(stayed);
            }
            return;
        }
        JailRecord record = jailed.get(p.getUniqueId());
        if (record != null) {
            if (record.isExpired(System.currentTimeMillis())) {
                releaseFromJail(p.getUniqueId(), p);
            } else {
                Location jailLoc = jails.get(record.jailName);
                if (jailLoc != null && jailLoc.getWorld() != null) {
                    if (!jailLoc.getWorld().equals(event.getTo().getWorld())
                            || jailLoc.distanceSquared(event.getTo()) > 64.0) {
                        event.setTo(jailLoc);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (frozen.contains(id) || isJailedActive(id)) {
            event.setCancelled(true);
            send(event.getPlayer(), ChatColor.RED + "You cannot break blocks right now.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (frozen.contains(id) || isJailedActive(id)) {
            event.setCancelled(true);
            send(event.getPlayer(), ChatColor.RED + "You cannot place blocks right now.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            UUID id = event.getDamager().getUniqueId();
            if (frozen.contains(id) || isJailedActive(id)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        String msg = event.getMessage();
        if (isJailedActive(p.getUniqueId()) && !msg.toLowerCase(Locale.ROOT).startsWith("/helpop")) {
            event.setCancelled(true);
            send(p, ChatColor.RED + "You cannot run commands while jailed (use /helpop for staff).");
            return;
        }
        String formatted = ChatColor.DARK_GRAY + "[CommandSpy] " + ChatColor.GRAY + p.getName()
                + ": " + ChatColor.WHITE + msg;
        for (UUID spyId : commandSpy) {
            Player spy = plugin.getServer().getPlayer(spyId);
            if (spy == null || spy.equals(p)) continue;
            if (spy.hasPermission("allium.commandspy")) {
                spy.sendMessage(formatted);
            }
        }
    }

    boolean handles(String name) {
        switch (name) {
            case "vanish":
            case "mute":
            case "unmute":
            case "mutes":
            case "kick":
            case "kickall":
            case "ban":
            case "tempban":
            case "unban":
            case "banlist":
            case "ipban":
            case "tempbanip":
            case "unbanip":
            case "freeze":
            case "setjail":
            case "deljail":
            case "jails":
            case "jail":
            case "unjail":
            case "togglejail":
            case "jailedplayers":
            case "warn":
            case "notes":
            case "unnote":
            case "invsee":
            case "helpop":
            case "report":
            case "socialspy":
            case "commandspy":
            case "spy":
                return true;
            default:
                return false;
        }
    }

    boolean onCommand(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "vanish": return handleVanish(sender, args);
            case "mute": return handleMute(sender, args);
            case "unmute": return handleUnmute(sender, args);
            case "mutes": return handleMutes(sender);
            case "kick": return handleKick(sender, args);
            case "kickall": return handleKickAll(sender, args);
            case "ban": return handleBan(sender, args, false, false);
            case "tempban": return handleBan(sender, args, true, false);
            case "unban": return handleUnban(sender, args, false);
            case "banlist": return handleBanList(sender);
            case "ipban": return handleBan(sender, args, false, true);
            case "tempbanip": return handleBan(sender, args, true, true);
            case "unbanip": return handleUnban(sender, args, true);
            case "freeze": return handleFreeze(sender, args);
            case "setjail": return handleSetJail(sender, args);
            case "deljail": return handleDelJail(sender, args);
            case "jails": return handleJails(sender);
            case "jail": return handleJail(sender, args);
            case "unjail": return handleUnjail(sender, args);
            case "togglejail": return handleToggleJail(sender, args);
            case "jailedplayers": return handleJailedPlayers(sender);
            case "warn": return handleWarn(sender, args);
            case "notes": return handleNotes(sender, args);
            case "unnote": return handleUnnote(sender, args);
            case "invsee": return handleInvsee(sender, args);
            case "helpop": return handleHelpop(sender, args);
            case "report": return handleReport(sender, args);
            case "socialspy": return handleSocialSpy(sender);
            case "commandspy": return handleCommandSpy(sender);
            case "spy": return handleSpyAll(sender);
            default: return false;
        }
    }

    List<String> onTabComplete(CommandSender sender, String name, String[] args, String prefix) {
        switch (name) {
            case "vanish":
            case "mute":
            case "unmute":
            case "kick":
            case "ban":
            case "tempban":
            case "ipban":
            case "tempbanip":
            case "freeze":
            case "unjail":
            case "warn":
            case "notes":
            case "unnote":
            case "invsee":
            case "report":
                return args.length == 1 ? onlinePlayers(prefix) : Collections.<String>emptyList();
            case "deljail":
                return args.length == 1 ? filterJails(prefix) : Collections.<String>emptyList();
            case "jail":
            case "togglejail":
                if (args.length == 1) return onlinePlayers(prefix);
                if (args.length == 2) return filterJails(prefix);
                return Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }

    private boolean handleVanish(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.vanish")) {
            send(sender, ChatColor.RED + "You do not have permission to use /vanish.");
            return true;
        }
        Player target;
        if (args.length >= 1) {
            if (!sender.hasPermission("allium.vanish.others")) {
                send(sender, ChatColor.RED + "You do not have permission to vanish other players.");
                return true;
            }
            target = findOnline(args[0]);
            if (target == null) {
                send(sender, ChatColor.RED + "Player '" + args[0] + "' is not online.");
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            send(sender, ChatColor.RED + "Usage: /vanish <player>");
            return true;
        }
        boolean nowVanished;
        if (vanished.remove(target.getUniqueId())) {
            nowVanished = false;
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (!online.equals(target)) LegacyCompat.showPlayer(plugin, online, target);
            }
        } else {
            nowVanished = true;
            vanished.add(target.getUniqueId());
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (!online.equals(target) && !online.hasPermission("allium.vanish.see")) {
                    LegacyCompat.hidePlayer(plugin, online, target);
                }
            }
        }
        String state = nowVanished ? "enabled" : "disabled";
        send(target, ChatColor.GREEN + "Vanish " + state + ".");
        if (!target.equals(sender)) {
            send(sender, ChatColor.GREEN + "Vanish " + state + " for " + target.getName() + ".");
        }
        return true;
    }

    private boolean handleMute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.mute")) {
            send(sender, ChatColor.RED + "You do not have permission to use /mute.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /mute <player> [duration] [reason]");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' not found.");
            return true;
        }
        long expiresAt = 0L;
        int reasonStart = 1;
        if (args.length >= 2) {
            long dur = LegacyCompat.parseDurationMillis(args[1]);
            if (dur > 0L) {
                expiresAt = System.currentTimeMillis() + dur;
                reasonStart = 2;
            }
        }
        String reason = args.length > reasonStart ? joinArgs(args, reasonStart) : "Muted by " + sender.getName();
        String targetName = target.getName() != null ? target.getName() : args[0];
        mutes.put(target.getUniqueId(), new MuteEntry(targetName, expiresAt, reason));
        saveModeration();
        String durLabel = expiresAt > 0L ? " for " + LegacyCompat.formatDuration(expiresAt - System.currentTimeMillis()) : " permanently";
        send(sender, ChatColor.GREEN + "Muted " + targetName + durLabel + ": " + reason);
        if (target.isOnline() && target.getPlayer() != null) {
            send(target.getPlayer(), ChatColor.RED + "You have been muted" + durLabel + ": " + reason);
        }
        return true;
    }

    private boolean handleUnmute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.unmute")) {
            send(sender, ChatColor.RED + "You do not have permission to use /unmute.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /unmute <player>");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null || mutes.remove(target.getUniqueId()) == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' is not muted.");
            return true;
        }
        saveModeration();
        String targetName = target.getName() != null ? target.getName() : args[0];
        send(sender, ChatColor.GREEN + "Unmuted " + targetName + ".");
        if (target.isOnline() && target.getPlayer() != null) {
            send(target.getPlayer(), ChatColor.GREEN + "You have been unmuted.");
        }
        return true;
    }

    private boolean handleMutes(CommandSender sender) {
        if (!sender.hasPermission("allium.mutes")) {
            send(sender, ChatColor.RED + "You do not have permission to view mutes.");
            return true;
        }
        long now = System.currentTimeMillis();
        List<String> active = new ArrayList<String>();
        List<UUID> expired = new ArrayList<UUID>();
        for (Map.Entry<UUID, MuteEntry> e : mutes.entrySet()) {
            if (e.getValue().isExpired(now)) {
                expired.add(e.getKey());
            } else {
                String rem = e.getValue().expiresAt > 0L
                        ? LegacyCompat.formatDuration(e.getValue().expiresAt - now)
                        : "permanent";
                active.add(e.getValue().name + " (" + rem + ")");
            }
        }
        for (UUID id : expired) mutes.remove(id);
        if (!expired.isEmpty()) saveModeration();
        if (active.isEmpty()) {
            send(sender, ChatColor.GRAY + "No players are currently muted.");
            return true;
        }
        send(sender, ChatColor.LIGHT_PURPLE + "Muted players (" + active.size() + "): "
                + ChatColor.WHITE + joinList(active, ", "));
        return true;
    }

    private boolean handleKick(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.kick")) {
            send(sender, ChatColor.RED + "You do not have permission to use /kick.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /kick <player> [reason]");
            return true;
        }
        Player target = findOnline(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        String reason = args.length >= 2 ? LegacyCompat.color(joinArgs(args, 1)) : "Kicked by " + sender.getName();
        target.kickPlayer(reason);
        send(sender, ChatColor.GREEN + "Kicked " + target.getName() + ": " + reason);
        return true;
    }

    private boolean handleKickAll(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.kickall")) {
            send(sender, ChatColor.RED + "You do not have permission to use /kickall.");
            return true;
        }
        String reason = args.length >= 1 ? LegacyCompat.color(joinArgs(args, 0)) : "Kicked by " + sender.getName();
        int count = 0;
        for (Player p : new ArrayList<Player>(plugin.getServer().getOnlinePlayers())) {
            if (p.equals(sender) || p.hasPermission("allium.kickall.exempt")) continue;
            p.kickPlayer(reason);
            count++;
        }
        send(sender, ChatColor.GREEN + "Kicked " + count + " player(s).");
        return true;
    }

    private boolean handleBan(CommandSender sender, String[] args, boolean temp, boolean ip) {
        String perm = ip ? (temp ? "allium.tempbanip" : "allium.ipban") : (temp ? "allium.tempban" : "allium.ban");
        if (!sender.hasPermission(perm)) {
            send(sender, ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length < (temp ? 2 : 1)) {
            send(sender, ChatColor.RED + "Usage: /" + (temp ? (ip ? "tempbanip" : "tempban") + " <target> <duration> [reason]"
                    : (ip ? "ipban" : "ban") + " <target> [reason]"));
            return true;
        }
        Date expiry = null;
        int reasonStart = 1;
        if (temp) {
            long dur = LegacyCompat.parseDurationMillis(args[1]);
            if (dur <= 0L) {
                send(sender, ChatColor.RED + "Invalid duration '" + args[1] + "' (e.g. 30m, 2h, 7d).");
                return true;
            }
            expiry = new Date(System.currentTimeMillis() + dur);
            reasonStart = 2;
        }
        String reason = args.length > reasonStart ? LegacyCompat.color(joinArgs(args, reasonStart)) : "Banned by " + sender.getName();
        String targetKey = args[0];
        Player online = findOnline(args[0]);
        if (ip) {
            if (online != null && online.getAddress() != null && online.getAddress().getAddress() != null) {
                targetKey = online.getAddress().getAddress().getHostAddress();
            }
            plugin.getServer().getBanList(BanList.Type.IP).addBan(targetKey, reason, expiry, sender.getName());
            for (Player p : new ArrayList<Player>(plugin.getServer().getOnlinePlayers())) {
                if (p.getAddress() != null && p.getAddress().getAddress() != null
                        && targetKey.equals(p.getAddress().getAddress().getHostAddress())) {
                    p.kickPlayer(ChatColor.RED + "IP Banned: " + reason);
                }
            }
            send(sender, ChatColor.GREEN + "IP-banned " + targetKey + ": " + reason);
            return true;
        }
        if (online != null) targetKey = online.getName();
        plugin.getServer().getBanList(BanList.Type.NAME).addBan(targetKey, reason, expiry, sender.getName());
        if (online != null) {
            online.kickPlayer(ChatColor.RED + "Banned: " + reason);
        }
        send(sender, ChatColor.GREEN + "Banned " + targetKey + ": " + reason);
        return true;
    }

    private boolean handleUnban(CommandSender sender, String[] args, boolean ip) {
        String perm = ip ? "allium.unbanip" : "allium.unban";
        if (!sender.hasPermission(perm)) {
            send(sender, ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /" + (ip ? "unbanip <ip>" : "unban <player>"));
            return true;
        }
        BanList list = plugin.getServer().getBanList(ip ? BanList.Type.IP : BanList.Type.NAME);
        if (!list.isBanned(args[0])) {
            send(sender, ChatColor.RED + "'" + args[0] + "' is not banned.");
            return true;
        }
        list.pardon(args[0]);
        send(sender, ChatColor.GREEN + "Unbanned " + args[0] + ".");
        return true;
    }

    private boolean handleBanList(CommandSender sender) {
        if (!sender.hasPermission("allium.banlist")) {
            send(sender, ChatColor.RED + "You do not have permission to view bans.");
            return true;
        }
        Set<BanEntry> entries = plugin.getServer().getBanList(BanList.Type.NAME).getBanEntries();
        if (entries.isEmpty()) {
            send(sender, ChatColor.GRAY + "No players are banned.");
            return true;
        }
        List<String> names = new ArrayList<String>();
        for (BanEntry entry : entries) {
            names.add(entry.getTarget());
        }
        Collections.sort(names);
        send(sender, ChatColor.LIGHT_PURPLE + "Banned players (" + names.size() + "): "
                + ChatColor.WHITE + joinList(names, ", "));
        return true;
    }

    private boolean handleFreeze(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.freeze")) {
            send(sender, ChatColor.RED + "You do not have permission to use /freeze.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /freeze <player>");
            return true;
        }
        Player target = findOnline(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        if (frozen.remove(target.getUniqueId())) {
            send(target, ChatColor.GREEN + "You have been unfrozen.");
            send(sender, ChatColor.GREEN + "Unfroze " + target.getName() + ".");
        } else {
            frozen.add(target.getUniqueId());
            send(target, ChatColor.RED + "You have been frozen by staff.");
            send(sender, ChatColor.GREEN + "Froze " + target.getName() + ".");
        }
        return true;
    }

    private boolean handleSetJail(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, ChatColor.RED + "Only players can set jails.");
            return true;
        }
        if (!sender.hasPermission("allium.setjail")) {
            send(sender, ChatColor.RED + "You do not have permission to set jails.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /setjail <name>");
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        jails.put(key, ((Player) sender).getLocation().clone());
        saveModeration();
        send(sender, ChatColor.GREEN + "Jail '" + key + "' set.");
        return true;
    }

    private boolean handleDelJail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.deljail")) {
            send(sender, ChatColor.RED + "You do not have permission to delete jails.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /deljail <name>");
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        if (jails.remove(key) == null) {
            send(sender, ChatColor.RED + "Jail '" + args[0] + "' does not exist.");
            return true;
        }
        saveModeration();
        send(sender, ChatColor.GREEN + "Deleted jail '" + key + "'.");
        return true;
    }

    private boolean handleJails(CommandSender sender) {
        if (!sender.hasPermission("allium.jails")) {
            send(sender, ChatColor.RED + "You do not have permission to list jails.");
            return true;
        }
        if (jails.isEmpty()) {
            send(sender, ChatColor.GRAY + "No jails are set.");
            return true;
        }
        send(sender, ChatColor.LIGHT_PURPLE + "Jails (" + jails.size() + "): "
                + ChatColor.WHITE + joinList(new ArrayList<String>(jails.keySet()), ", "));
        return true;
    }

    private boolean handleJail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.jail")) {
            send(sender, ChatColor.RED + "You do not have permission to use /jail.");
            return true;
        }
        if (args.length < 2) {
            send(sender, ChatColor.RED + "Usage: /jail <player> <jail> [duration]");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' not found.");
            return true;
        }
        String jailKey = args[1].toLowerCase(Locale.ROOT);
        Location jailLoc = jails.get(jailKey);
        if (jailLoc == null) {
            send(sender, ChatColor.RED + "Jail '" + args[1] + "' does not exist.");
            return true;
        }
        long expiresAt = 0L;
        if (args.length >= 3) {
            long dur = LegacyCompat.parseDurationMillis(args[2]);
            if (dur <= 0L) {
                send(sender, ChatColor.RED + "Invalid duration '" + args[2] + "'.");
                return true;
            }
            expiresAt = System.currentTimeMillis() + dur;
        }
        Location prev = target.isOnline() && target.getPlayer() != null ? target.getPlayer().getLocation().clone() : null;
        String targetName = target.getName() != null ? target.getName() : args[0];
        jailed.put(target.getUniqueId(), new JailRecord(targetName, jailKey, expiresAt, prev));
        saveModeration();
        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().teleport(jailLoc);
            send(target.getPlayer(), ChatColor.RED + "You have been jailed in '" + jailKey + "'.");
        }
        send(sender, ChatColor.GREEN + "Jailed " + targetName + " in '" + jailKey + "'.");
        return true;
    }

    private boolean handleUnjail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.unjail")) {
            send(sender, ChatColor.RED + "You do not have permission to use /unjail.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /unjail <player>");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null || !jailed.containsKey(target.getUniqueId())) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' is not jailed.");
            return true;
        }
        releaseFromJail(target.getUniqueId(), target.isOnline() ? target.getPlayer() : null);
        send(sender, ChatColor.GREEN + "Released " + (target.getName() != null ? target.getName() : args[0]) + " from jail.");
        return true;
    }

    private boolean handleToggleJail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.jail")) {
            send(sender, ChatColor.RED + "You do not have permission to use /togglejail.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /togglejail <player> [jail] [duration]");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target != null && jailed.containsKey(target.getUniqueId())) {
            return handleUnjail(sender, new String[]{args[0]});
        }
        if (args.length < 2) {
            if (jails.isEmpty()) {
                send(sender, ChatColor.RED + "No jails are configured.");
                return true;
            }
            String firstJail = jails.keySet().iterator().next();
            return handleJail(sender, new String[]{args[0], firstJail});
        }
        return handleJail(sender, args);
    }

    private boolean handleJailedPlayers(CommandSender sender) {
        if (!sender.hasPermission("allium.jailedplayers") && !sender.hasPermission("allium.jails")) {
            send(sender, ChatColor.RED + "You do not have permission to list jailed players.");
            return true;
        }
        if (jailed.isEmpty()) {
            send(sender, ChatColor.GRAY + "No players are currently jailed.");
            return true;
        }
        List<String> list = new ArrayList<String>();
        for (JailRecord r : jailed.values()) {
            list.add(r.name + " (" + r.jailName + ")");
        }
        send(sender, ChatColor.LIGHT_PURPLE + "Jailed players (" + list.size() + "): "
                + ChatColor.WHITE + joinList(list, ", "));
        return true;
    }

    private boolean handleWarn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.warn")) {
            send(sender, ChatColor.RED + "You do not have permission to use /warn.");
            return true;
        }
        if (args.length < 2) {
            send(sender, ChatColor.RED + "Usage: /warn <player> <reason>");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' not found.");
            return true;
        }
        String reason = joinArgs(args, 1);
        List<String> list = notes.get(target.getUniqueId());
        if (list == null) {
            list = new ArrayList<String>();
            notes.put(target.getUniqueId(), list);
        }
        list.add("[WARN by " + sender.getName() + "] " + reason);
        saveModeration();
        String targetName = target.getName() != null ? target.getName() : args[0];
        send(sender, ChatColor.GREEN + "Warned " + targetName + " (#" + list.size() + "): " + reason);
        if (target.isOnline() && target.getPlayer() != null) {
            send(target.getPlayer(), ChatColor.RED + "Warning from " + sender.getName() + ": " + ChatColor.YELLOW + reason);
        }
        return true;
    }

    private boolean handleNotes(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.notes")) {
            send(sender, ChatColor.RED + "You do not have permission to use /notes.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /notes <player> [note to add]");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' not found.");
            return true;
        }
        String targetName = target.getName() != null ? target.getName() : args[0];
        if (args.length >= 2) {
            List<String> list = notes.get(target.getUniqueId());
            if (list == null) {
                list = new ArrayList<String>();
                notes.put(target.getUniqueId(), list);
            }
            list.add("[NOTE by " + sender.getName() + "] " + joinArgs(args, 1));
            saveModeration();
            send(sender, ChatColor.GREEN + "Added note #" + list.size() + " for " + targetName + ".");
            return true;
        }
        List<String> list = notes.get(target.getUniqueId());
        if (list == null || list.isEmpty()) {
            send(sender, ChatColor.GRAY + targetName + " has no warnings or notes.");
            return true;
        }
        send(sender, ChatColor.LIGHT_PURPLE + "Notes for " + targetName + " (" + list.size() + "):");
        for (int i = 0; i < list.size(); i++) {
            sender.sendMessage(ChatColor.GRAY + "" + (i + 1) + ". " + ChatColor.WHITE + list.get(i));
        }
        return true;
    }

    private boolean handleUnnote(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.unnote") && !sender.hasPermission("allium.notes")) {
            send(sender, ChatColor.RED + "You do not have permission to remove notes.");
            return true;
        }
        if (args.length < 2) {
            send(sender, ChatColor.RED + "Usage: /unnote <player> <index>");
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' not found.");
            return true;
        }
        List<String> list = notes.get(target.getUniqueId());
        int idx;
        try {
            idx = Integer.parseInt(args[1]) - 1;
        } catch (NumberFormatException e) {
            send(sender, ChatColor.RED + "Enter a valid note number.");
            return true;
        }
        if (list == null || idx < 0 || idx >= list.size()) {
            send(sender, ChatColor.RED + "No note #" + args[1] + " found.");
            return true;
        }
        list.remove(idx);
        if (list.isEmpty()) notes.remove(target.getUniqueId());
        saveModeration();
        send(sender, ChatColor.GREEN + "Removed note #" + (idx + 1) + ".");
        return true;
    }

    private boolean handleInvsee(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, ChatColor.RED + "Only players can use /invsee.");
            return true;
        }
        if (!sender.hasPermission("allium.invsee")) {
            send(sender, ChatColor.RED + "You do not have permission to use /invsee.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /invsee <player>");
            return true;
        }
        Player target = findOnline(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        ((Player) sender).openInventory(target.getInventory());
        return true;
    }

    private boolean handleHelpop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.helpop")) {
            send(sender, ChatColor.RED + "You do not have permission to use /helpop.");
            return true;
        }
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /helpop <message>");
            return true;
        }
        String text = joinArgs(args, 0);
        String msg = ChatColor.DARK_RED + "[HelpOp] " + ChatColor.WHITE + sender.getName() + ChatColor.GRAY + ": " + ChatColor.YELLOW + text;
        int notified = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasPermission("allium.helpop.receive") || p.isOp()) {
                p.sendMessage(msg);
                notified++;
            }
        }
        plugin.getLogger().info("[HelpOp] " + sender.getName() + ": " + text);
        send(sender, ChatColor.GREEN + "Sent HelpOp request to " + notified + " online staff member(s).");
        return true;
    }

    private boolean handleReport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.report")) {
            send(sender, ChatColor.RED + "You do not have permission to use /report.");
            return true;
        }
        if (args.length < 2) {
            send(sender, ChatColor.RED + "Usage: /report <player> <reason>");
            return true;
        }
        String target = args[0];
        String reason = joinArgs(args, 1);
        String alert = ChatColor.RED + "[Report] " + ChatColor.WHITE + sender.getName()
                + ChatColor.GRAY + " reported " + ChatColor.YELLOW + target
                + ChatColor.GRAY + ": " + ChatColor.WHITE + reason;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasPermission("allium.report.receive") || p.hasPermission("allium.helpop.receive") || p.isOp()) {
                send(p, alert);
            }
        }
        plugin.getLogger().info("[Report] " + sender.getName() + " reported " + target + ": " + reason);
        send(sender, ChatColor.GREEN + "Report submitted against " + target + ".");
        return true;
    }

    private boolean handleSocialSpy(CommandSender sender) {
        if (!(sender instanceof Player)) {
            send(sender, ChatColor.RED + "Only players can toggle socialspy.");
            return true;
        }
        if (!sender.hasPermission("allium.socialspy")) {
            send(sender, ChatColor.RED + "You do not have permission to use /socialspy.");
            return true;
        }
        UUID id = ((Player) sender).getUniqueId();
        if (socialSpy.remove(id)) {
            send(sender, ChatColor.GRAY + "SocialSpy disabled.");
        } else {
            socialSpy.add(id);
            send(sender, ChatColor.GREEN + "SocialSpy enabled.");
        }
        return true;
    }

    private boolean handleCommandSpy(CommandSender sender) {
        if (!(sender instanceof Player)) {
            send(sender, ChatColor.RED + "Only players can toggle commandspy.");
            return true;
        }
        if (!sender.hasPermission("allium.commandspy")) {
            send(sender, ChatColor.RED + "You do not have permission to use /commandspy.");
            return true;
        }
        UUID id = ((Player) sender).getUniqueId();
        if (commandSpy.remove(id)) {
            send(sender, ChatColor.GRAY + "CommandSpy disabled.");
        } else {
            commandSpy.add(id);
            send(sender, ChatColor.GREEN + "CommandSpy enabled.");
        }
        return true;
    }

    private boolean handleSpyAll(CommandSender sender) {
        if (!(sender instanceof Player)) {
            send(sender, ChatColor.RED + "Only players can toggle spy.");
            return true;
        }
        if (!sender.hasPermission("allium.spy") && !sender.hasPermission("allium.socialspy")) {
            send(sender, ChatColor.RED + "You do not have permission to use /spy.");
            return true;
        }
        UUID id = ((Player) sender).getUniqueId();
        boolean enable = !socialSpy.contains(id) || !commandSpy.contains(id);
        if (enable) {
            socialSpy.add(id);
            commandSpy.add(id);
            send(sender, ChatColor.GREEN + "SocialSpy and CommandSpy enabled.");
        } else {
            socialSpy.remove(id);
            commandSpy.remove(id);
            send(sender, ChatColor.GRAY + "SocialSpy and CommandSpy disabled.");
        }
        return true;
    }

    private boolean isJailedActive(UUID id) {
        JailRecord r = jailed.get(id);
        if (r == null) return false;
        if (r.isExpired(System.currentTimeMillis())) {
            releaseFromJail(id, plugin.getServer().getPlayer(id));
            return false;
        }
        return true;
    }

    private void releaseFromJail(UUID id, Player online) {
        JailRecord removed = jailed.remove(id);
        saveModeration();
        if (online != null && removed != null) {
            if (removed.returnLocation != null && removed.returnLocation.getWorld() != null) {
                online.teleport(removed.returnLocation);
            } else if (online.getWorld() != null) {
                online.teleport(online.getWorld().getSpawnLocation());
            }
            send(online, ChatColor.GREEN + "You have been released from jail.");
        }
    }

    private Player findOnline(String name) {
        Player p = plugin.getServer().getPlayerExact(name);
        return p != null ? p : plugin.getServer().getPlayer(name);
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer resolvePlayer(String name) {
        Player p = findOnline(name);
        if (p != null) return p;
        return plugin.getServer().getOfflinePlayer(name);
    }

    private void loadModeration() {
        mutes.clear();
        jails.clear();
        jailed.clear();
        notes.clear();
        File file = new File(plugin.getDataFolder(), "moderation-legacy.yml");
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        long now = System.currentTimeMillis();

        ConfigurationSection muteSec = yml.getConfigurationSection("mutes");
        if (muteSec != null) {
            for (String key : muteSec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    ConfigurationSection s = muteSec.getConfigurationSection(key);
                    if (s == null) continue;
                    MuteEntry entry = new MuteEntry(s.getString("name", key), s.getLong("expires", 0L), s.getString("reason", ""));
                    if (!entry.isExpired(now)) mutes.put(id, entry);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection jailSec = yml.getConfigurationSection("jails");
        if (jailSec != null) {
            for (String key : jailSec.getKeys(false)) {
                Location loc = LegacyCompat.readLocation(plugin.getServer(), jailSec.getConfigurationSection(key));
                if (loc != null) jails.put(key.toLowerCase(Locale.ROOT), loc);
            }
        }
        ConfigurationSection jailedSec = yml.getConfigurationSection("jailed");
        if (jailedSec != null) {
            for (String key : jailedSec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    ConfigurationSection s = jailedSec.getConfigurationSection(key);
                    if (s == null) continue;
                    Location ret = LegacyCompat.readLocation(plugin.getServer(), s.getConfigurationSection("return"));
                    JailRecord rec = new JailRecord(s.getString("name", key), s.getString("jail", ""), s.getLong("expires", 0L), ret);
                    if (!rec.isExpired(now)) jailed.put(id, rec);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection noteSec = yml.getConfigurationSection("notes");
        if (noteSec != null) {
            for (String key : noteSec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    List<String> list = noteSec.getStringList(key);
                    if (!list.isEmpty()) notes.put(id, new ArrayList<String>(list));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveModeration() {
        File file = new File(plugin.getDataFolder(), "moderation-legacy.yml");
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, MuteEntry> e : mutes.entrySet()) {
            String path = "mutes." + e.getKey();
            yml.set(path + ".name", e.getValue().name);
            yml.set(path + ".expires", e.getValue().expiresAt);
            yml.set(path + ".reason", e.getValue().reason);
        }
        for (Map.Entry<String, Location> e : jails.entrySet()) {
            LegacyCompat.writeLocation(yml.createSection("jails." + e.getKey()), e.getValue());
        }
        for (Map.Entry<UUID, JailRecord> e : jailed.entrySet()) {
            String path = "jailed." + e.getKey();
            yml.set(path + ".name", e.getValue().name);
            yml.set(path + ".jail", e.getValue().jailName);
            yml.set(path + ".expires", e.getValue().expiresAt);
            if (e.getValue().returnLocation != null) {
                LegacyCompat.writeLocation(yml.createSection(path + ".return"), e.getValue().returnLocation);
            }
        }
        for (Map.Entry<UUID, List<String>> e : notes.entrySet()) {
            yml.set("notes." + e.getKey(), e.getValue());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save moderation-legacy.yml: " + e.getMessage());
        }
    }

    private List<String> onlinePlayers(String prefix) {
        List<String> out = new ArrayList<String>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
        }
        Collections.sort(out);
        return out;
    }

    private List<String> filterJails(String prefix) {
        List<String> out = new ArrayList<String>();
        for (String j : jails.keySet()) {
            if (j.startsWith(prefix)) out.add(j);
        }
        Collections.sort(out);
        return out;
    }

    private static String joinArgs(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static String joinList(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
