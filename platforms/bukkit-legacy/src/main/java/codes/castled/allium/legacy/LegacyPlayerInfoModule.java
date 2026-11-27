package codes.castled.allium.legacy;

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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Java 8 / 1.12.2-1.19.4 player info, AFK, ignore, mail, and nickname module:
 * {@code /list}, {@code /seen}, {@code /whois}, {@code /near}, {@code /playtime}, {@code /afk},
 * {@code /ignore}, {@code /unignore}, {@code /mail}, {@code /nick}, {@code /realname}, {@code /action}.
 */
final class LegacyPlayerInfoModule implements Listener {

    private final JavaPlugin plugin;
    private final LegacyModerationModule moderation;
    private final LegacyEconomyModule economy;
    private final Map<UUID, String> lastNames = new HashMap<UUID, String>();
    private final Map<UUID, Long> sessionStart = new HashMap<UUID, Long>();
    private final Map<UUID, Long> savedPlaytimeMs = new HashMap<UUID, Long>();
    private final Map<UUID, Long> lastSeenMs = new HashMap<UUID, Long>();
    private final Map<UUID, Location> lastLocations = new HashMap<UUID, Location>();
    private final Map<UUID, String> nicknames = new HashMap<UUID, String>();
    private final Map<UUID, String> afkReasons = new HashMap<UUID, String>();
    private final Map<UUID, Set<UUID>> ignores = new HashMap<UUID, Set<UUID>>();
    private final Map<UUID, List<String>> mailboxes = new HashMap<UUID, List<String>>();

    LegacyPlayerInfoModule(JavaPlugin plugin, LegacyModerationModule moderation, LegacyEconomyModule economy) {
        this.plugin = plugin;
        this.moderation = moderation;
        this.economy = economy;
    }

    void enable() {
        loadInfo();
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            sessionStart.put(p.getUniqueId(), now);
            lastNames.put(p.getUniqueId(), p.getName());
            applyNick(p);
        }
    }

    void reload() {
        loadInfo();
    }

    void disable() {
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            recordQuit(p, now);
        }
        saveInfo();
        afkReasons.clear();
        sessionStart.clear();
    }

    boolean isIgnoring(UUID recipient, UUID sender) {
        Set<UUID> set = ignores.get(recipient);
        return set != null && set.contains(sender);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        lastNames.put(id, p.getName());
        sessionStart.put(id, System.currentTimeMillis());
        applyNick(p);
        List<String> mail = mailboxes.get(id);
        if (mail != null && !mail.isEmpty()) {
            p.sendMessage(ChatColor.LIGHT_PURPLE + "You have " + ChatColor.WHITE + mail.size()
                    + ChatColor.LIGHT_PURPLE + " unread mail message(s). Use " + ChatColor.WHITE + "/mail read" + ChatColor.LIGHT_PURPLE + ".");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        recordQuit(event.getPlayer(), System.currentTimeMillis());
        saveInfo();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom() == null) return;
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            clearAfkIfActive(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        clearAfkIfActive(sender);
        Iterator<Player> it = event.getRecipients().iterator();
        while (it.hasNext()) {
            Player recipient = it.next();
            if (isIgnoring(recipient.getUniqueId(), sender.getUniqueId())) {
                it.remove();
            }
        }
    }

    boolean handles(String name) {
        switch (name) {
            case "list":
            case "seen":
            case "whois":
            case "near":
            case "playtime":
            case "afk":
            case "ignore":
            case "unignore":
            case "mail":
            case "nick":
            case "realname":
            case "action":
                return true;
            default:
                return false;
        }
    }

    boolean onCommand(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "list": return handleList(sender);
            case "seen": return handleSeen(sender, args);
            case "whois": return handleWhois(sender, args);
            case "near": return handleNear(sender, args);
            case "playtime": return handlePlaytime(sender, args);
            case "afk": return handleAfk(sender, args);
            case "ignore": return handleIgnore(sender, args, true);
            case "unignore": return handleIgnore(sender, args, false);
            case "mail": return handleMail(sender, args);
            case "nick": return handleNick(sender, args);
            case "realname": return handleRealName(sender, args);
            case "action": return handleAction(sender, args);
            default: return false;
        }
    }

    List<String> onTabComplete(CommandSender sender, String name, String[] args, String prefix) {
        switch (name) {
            case "seen":
            case "whois":
            case "playtime":
            case "ignore":
            case "unignore":
                return args.length == 1 ? onlinePlayers(prefix) : Collections.<String>emptyList();
            case "nick":
                return args.length == 2 ? onlinePlayers(prefix) : Collections.<String>emptyList();
            case "mail":
                if (args.length == 1) return filter(Arrays.asList("read", "send", "clear"), prefix);
                if (args.length == 2 && args[0].equalsIgnoreCase("send")) return onlinePlayers(prefix);
                return Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }

    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("allium.list")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /list.");
            return true;
        }
        boolean seeVanished = sender.hasPermission("allium.vanish.see");
        List<String> visible = new ArrayList<String>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (moderation.isVanished(p.getUniqueId()) && !seeVanished) continue;
            String label = p.getName();
            if (afkReasons.containsKey(p.getUniqueId())) label += ChatColor.GRAY + " [AFK]" + ChatColor.WHITE;
            if (moderation.isVanished(p.getUniqueId())) label += ChatColor.GRAY + " [V]" + ChatColor.WHITE;
            visible.add(label);
        }
        Collections.sort(visible);
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Online (" + visible.size() + "/" + plugin.getServer().getMaxPlayers() + "): "
                + ChatColor.WHITE + (visible.isEmpty() ? "none" : joinList(visible, ", ")));
        return true;
    }

    private boolean handleSeen(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.seen")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /seen.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /seen <player>");
            return true;
        }
        Player online = findOnline(args[0]);
        if (online != null && (!moderation.isVanished(online.getUniqueId()) || sender.hasPermission("allium.vanish.see"))) {
            long start = sessionStart.containsKey(online.getUniqueId()) ? sessionStart.get(online.getUniqueId()) : System.currentTimeMillis();
            sender.sendMessage(ChatColor.GREEN + online.getName() + " is online (for "
                    + LegacyCompat.formatDuration(System.currentTimeMillis() - start) + ").");
            return true;
        }
        OfflinePlayer op = resolvePlayer(args[0]);
        if (op == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' has never joined.");
            return true;
        }
        long last = lastSeenMs.containsKey(op.getUniqueId()) ? lastSeenMs.get(op.getUniqueId()) : op.getLastPlayed();
        if (last <= 0L) {
            sender.sendMessage(ChatColor.RED + "No last-seen record for '" + args[0] + "'.");
            return true;
        }
        String name = op.getName() != null ? op.getName() : args[0];
        sender.sendMessage(ChatColor.GRAY + name + " was last seen " + ChatColor.WHITE
                + LegacyCompat.formatDuration(Math.max(0L, System.currentTimeMillis() - last)) + ChatColor.GRAY + " ago.");
        return true;
    }

    private boolean handleWhois(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.whois")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /whois.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /whois <player>");
            return true;
        }
        Player target = findOnline(args[0]);
        if (target == null) {
            return handleSeen(sender, args);
        }
        UUID id = target.getUniqueId();
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "=== Whois: " + target.getName() + " ===");
        sender.sendMessage(ChatColor.GRAY + "UUID: " + ChatColor.WHITE + id);
        if (nicknames.containsKey(id)) {
            sender.sendMessage(ChatColor.GRAY + "Nick: " + ChatColor.WHITE + LegacyCompat.color(nicknames.get(id)));
        }
        sender.sendMessage(ChatColor.GRAY + "Health: " + ChatColor.WHITE + String.format(Locale.US, "%.1f/%.1f", target.getHealth(), LegacyCompat.maxHealth(target))
                + ChatColor.GRAY + " | Food: " + ChatColor.WHITE + target.getFoodLevel() + "/20"
                + ChatColor.GRAY + " | GM: " + ChatColor.WHITE + target.getGameMode());
        Location loc = target.getLocation();
        sender.sendMessage(ChatColor.GRAY + "Location: " + ChatColor.WHITE + loc.getWorld().getName()
                + " (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")");
        sender.sendMessage(ChatColor.GRAY + "Balance: " + ChatColor.GREEN + LegacyCompat.formatMoney(economy.getBalance(id))
                + ChatColor.GRAY + " | Playtime: " + ChatColor.WHITE + LegacyCompat.formatDuration(totalPlaytimeMs(id)));
        sender.sendMessage(ChatColor.GRAY + "AFK: " + (afkReasons.containsKey(id) ? ChatColor.YELLOW + "Yes" : ChatColor.GREEN + "No")
                + ChatColor.GRAY + " | Muted: " + (moderation.isMuted(id) ? ChatColor.RED + "Yes" : ChatColor.GREEN + "No")
                + ChatColor.GRAY + " | Vanished: " + (moderation.isVanished(id) ? ChatColor.YELLOW + "Yes" : ChatColor.GREEN + "No"));
        if (sender.hasPermission("allium.whois.ip") && target.getAddress() != null && target.getAddress().getAddress() != null) {
            sender.sendMessage(ChatColor.GRAY + "IP: " + ChatColor.WHITE + target.getAddress().getAddress().getHostAddress());
        }
        return true;
    }

    private boolean handleNear(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /near.");
            return true;
        }
        if (!sender.hasPermission("allium.near")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /near.");
            return true;
        }
        double radius = 200.0;
        if (args.length >= 1) {
            try {
                radius = Math.max(1.0, Math.min(2000.0, Double.parseDouble(args[0])));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid radius '" + args[0] + "'.");
                return true;
            }
        }
        Player self = (Player) sender;
        boolean seeVanished = self.hasPermission("allium.vanish.see");
        double rSq = radius * radius;
        List<String> found = new ArrayList<String>();
        for (Player other : self.getWorld().getPlayers()) {
            if (other.equals(self)) continue;
            if (moderation.isVanished(other.getUniqueId()) && !seeVanished) continue;
            double dSq = other.getLocation().distanceSquared(self.getLocation());
            if (dSq <= rSq) {
                found.add(other.getName() + ChatColor.GRAY + " (" + Math.round(Math.sqrt(dSq)) + "m)" + ChatColor.WHITE);
            }
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Players within " + Math.round(radius) + "m: "
                + ChatColor.WHITE + (found.isEmpty() ? "none" : joinList(found, ", ")));
        return true;
    }

    private boolean handlePlaytime(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.playtime")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /playtime.");
            return true;
        }
        OfflinePlayer target;
        if (args.length >= 1) {
            target = resolvePlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' not found.");
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /playtime <player>");
            return true;
        }
        String name = target.getName() != null ? target.getName() : args[0];
        sender.sendMessage(ChatColor.GREEN + name + "'s playtime: " + ChatColor.WHITE
                + LegacyCompat.formatDuration(totalPlaytimeMs(target.getUniqueId())));
        return true;
    }

    private boolean handleAfk(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can toggle AFK.");
            return true;
        }
        if (!sender.hasPermission("allium.afk")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /afk.");
            return true;
        }
        Player p = (Player) sender;
        if (afkReasons.remove(p.getUniqueId()) != null) {
            plugin.getServer().broadcastMessage(ChatColor.GRAY + "* " + p.getName() + " is no longer AFK.");
        } else {
            String reason = args.length > 0 ? joinArgs(args, 0) : "";
            afkReasons.put(p.getUniqueId(), reason);
            String suffix = reason.isEmpty() ? "." : " (" + reason + ").";
            plugin.getServer().broadcastMessage(ChatColor.GRAY + "* " + p.getName() + " is now AFK" + suffix);
        }
        return true;
    }

    private boolean handleIgnore(CommandSender sender, String[] args, boolean add) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can ignore other players.");
            return true;
        }
        String perm = add ? "allium.ignore" : "allium.unignore";
        if (!sender.hasPermission(perm)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        Player self = (Player) sender;
        if (args.length < 1) {
            Set<UUID> list = ignores.get(self.getUniqueId());
            if (list == null || list.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "You are not ignoring anyone.");
            } else {
                List<String> names = new ArrayList<String>();
                for (UUID u : list) {
                    String n = lastNames.get(u);
                    names.add(n != null ? n : u.toString());
                }
                sender.sendMessage(ChatColor.GRAY + "Ignoring: " + ChatColor.WHITE + joinList(names, ", "));
            }
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' not found.");
            return true;
        }
        if (target.getUniqueId().equals(self.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You cannot ignore yourself.");
            return true;
        }
        String targetName = target.getName() != null ? target.getName() : args[0];
        Set<UUID> set = ignores.get(self.getUniqueId());
        if (add) {
            if (set == null) {
                set = new HashSet<UUID>();
                ignores.put(self.getUniqueId(), set);
            }
            set.add(target.getUniqueId());
            saveInfo();
            sender.sendMessage(ChatColor.GREEN + "Now ignoring " + targetName + ".");
        } else {
            if (set == null || !set.remove(target.getUniqueId())) {
                sender.sendMessage(ChatColor.RED + "You are not ignoring " + targetName + ".");
                return true;
            }
            if (set.isEmpty()) ignores.remove(self.getUniqueId());
            saveInfo();
            sender.sendMessage(ChatColor.GREEN + "No longer ignoring " + targetName + ".");
        }
        return true;
    }

    private boolean handleMail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.mail")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /mail.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("read")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /mail send <player> <message>");
                return true;
            }
            List<String> mail = mailboxes.get(((Player) sender).getUniqueId());
            if (mail == null || mail.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Your mailbox is empty.");
                return true;
            }
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Mailbox (" + mail.size() + "):");
            for (int i = 0; i < mail.size(); i++) {
                sender.sendMessage(ChatColor.GRAY + "" + (i + 1) + ". " + ChatColor.WHITE + mail.get(i));
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("clear")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can clear their mailbox.");
                return true;
            }
            mailboxes.remove(((Player) sender).getUniqueId());
            saveInfo();
            sender.sendMessage(ChatColor.GREEN + "Cleared your mailbox.");
            return true;
        }
        if (sub.equals("send")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /mail send <player> <message>");
                return true;
            }
            OfflinePlayer target = resolvePlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' not found.");
                return true;
            }
            if (sender instanceof Player && isIgnoring(target.getUniqueId(), ((Player) sender).getUniqueId())) {
                sender.sendMessage(ChatColor.RED + "That player is ignoring you.");
                return true;
            }
            String text = joinArgs(args, 2);
            List<String> list = mailboxes.get(target.getUniqueId());
            if (list == null) {
                list = new ArrayList<String>();
                mailboxes.put(target.getUniqueId(), list);
            }
            if (list.size() >= 50) {
                sender.sendMessage(ChatColor.RED + "That player's mailbox is full.");
                return true;
            }
            list.add(sender.getName() + ": " + text);
            saveInfo();
            String targetName = target.getName() != null ? target.getName() : args[1];
            sender.sendMessage(ChatColor.GREEN + "Sent mail to " + targetName + ".");
            if (target.isOnline() && target.getPlayer() != null) {
                target.getPlayer().sendMessage(ChatColor.LIGHT_PURPLE + "New mail from " + ChatColor.WHITE + sender.getName()
                        + ChatColor.LIGHT_PURPLE + ". Use " + ChatColor.WHITE + "/mail read" + ChatColor.LIGHT_PURPLE + ".");
            }
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /mail <read|send|clear>");
        return true;
    }

    private boolean handleNick(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.nick")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /nick.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /nick <nickname|off> [player]");
            return true;
        }
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("allium.nick.others")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to change other players' nicknames.");
                return true;
            }
            target = findOnline(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online.");
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /nick <nickname|off> <player>");
            return true;
        }
        if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("reset")) {
            nicknames.remove(target.getUniqueId());
            target.setDisplayName(target.getName());
            saveInfo();
            sender.sendMessage(ChatColor.GREEN + "Reset nickname for " + target.getName() + ".");
            return true;
        }
        String raw = args[0];
        String plain = ChatColor.stripColor(LegacyCompat.color(raw));
        if (plain.isEmpty() || plain.length() > 32) {
            sender.sendMessage(ChatColor.RED + "Nickname must be 1-32 visible characters.");
            return true;
        }
        nicknames.put(target.getUniqueId(), raw);
        applyNick(target);
        saveInfo();
        sender.sendMessage(ChatColor.GREEN + "Set nickname for " + target.getName() + " to " + LegacyCompat.color(raw) + ChatColor.GREEN + ".");
        return true;
    }

    private boolean handleRealName(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.realname")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /realname.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /realname <nickname>");
            return true;
        }
        String needle = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<String>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            String plainDisplay = ChatColor.stripColor(p.getDisplayName()).toLowerCase(Locale.ROOT);
            if (plainDisplay.contains(needle) || p.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(p.getDisplayName() + ChatColor.GRAY + " -> " + ChatColor.WHITE + p.getName());
            }
        }
        if (matches.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No online player matches '" + args[0] + "'.");
            return true;
        }
        for (String m : matches) sender.sendMessage(m);
        return true;
    }

    private boolean handleAction(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.action")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /action.");
            return true;
        }
        if (sender instanceof Player && moderation.isMuted(((Player) sender).getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You cannot use /action while muted.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /action <message>");
            return true;
        }
        String text = joinArgs(args, 0);
        String msg = ChatColor.GRAY + "* " + sender.getName() + " " + text;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (sender instanceof Player && isIgnoring(p.getUniqueId(), ((Player) sender).getUniqueId())) continue;
            p.sendMessage(msg);
        }
        plugin.getLogger().info("* " + sender.getName() + " " + text);
        return true;
    }

    private void applyNick(Player p) {
        String nick = nicknames.get(p.getUniqueId());
        if (nick != null && !nick.isEmpty()) {
            p.setDisplayName(LegacyCompat.color(nick) + ChatColor.RESET);
        }
    }

    private void clearAfkIfActive(Player p) {
        if (afkReasons.remove(p.getUniqueId()) != null) {
            plugin.getServer().broadcastMessage(ChatColor.GRAY + "* " + p.getName() + " is no longer AFK.");
        }
    }

    private long totalPlaytimeMs(UUID id) {
        long base = savedPlaytimeMs.containsKey(id) ? savedPlaytimeMs.get(id) : 0L;
        Long start = sessionStart.get(id);
        if (start != null) {
            base += Math.max(0L, System.currentTimeMillis() - start);
        }
        return base;
    }

    private void recordQuit(Player p, long now) {
        UUID id = p.getUniqueId();
        lastNames.put(id, p.getName());
        lastSeenMs.put(id, now);
        if (p.getLocation() != null && p.getLocation().getWorld() != null) {
            lastLocations.put(id, p.getLocation().clone());
        }
        Long start = sessionStart.remove(id);
        if (start != null) {
            long prev = savedPlaytimeMs.containsKey(id) ? savedPlaytimeMs.get(id) : 0L;
            savedPlaytimeMs.put(id, prev + Math.max(0L, now - start));
        }
        afkReasons.remove(id);
    }

    private Player findOnline(String name) {
        Player p = plugin.getServer().getPlayerExact(name);
        return p != null ? p : plugin.getServer().getPlayer(name);
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer resolvePlayer(String name) {
        Player p = findOnline(name);
        if (p != null) return p;
        for (Map.Entry<UUID, String> e : lastNames.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(name)) {
                return plugin.getServer().getOfflinePlayer(e.getKey());
            }
        }
        OfflinePlayer op = plugin.getServer().getOfflinePlayer(name);
        return op != null && (op.hasPlayedBefore() || lastSeenMs.containsKey(op.getUniqueId())) ? op : null;
    }

    private void loadInfo() {
        lastNames.clear();
        savedPlaytimeMs.clear();
        lastSeenMs.clear();
        lastLocations.clear();
        nicknames.clear();
        ignores.clear();
        mailboxes.clear();
        File file = new File(plugin.getDataFolder(), "player-info-legacy.yml");
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection s = players.getConfigurationSection(key);
                if (s == null) continue;
                String n = s.getString("name");
                if (n != null) lastNames.put(id, n);
                long pt = s.getLong("playtime-ms", 0L);
                if (pt > 0L) savedPlaytimeMs.put(id, pt);
                long ls = s.getLong("last-seen", 0L);
                if (ls > 0L) lastSeenMs.put(id, ls);
                Location loc = LegacyCompat.readLocation(plugin.getServer(), s.getConfigurationSection("last-location"));
                if (loc != null) lastLocations.put(id, loc);
                String nick = s.getString("nick");
                if (nick != null && !nick.isEmpty()) nicknames.put(id, nick);
                List<String> ign = s.getStringList("ignores");
                if (!ign.isEmpty()) {
                    Set<UUID> set = new HashSet<UUID>();
                    for (String u : ign) {
                        try { set.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) {}
                    }
                    if (!set.isEmpty()) ignores.put(id, set);
                }
                List<String> mail = s.getStringList("mail");
                if (!mail.isEmpty()) mailboxes.put(id, new ArrayList<String>(mail));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveInfo() {
        File file = new File(plugin.getDataFolder(), "player-info-legacy.yml");
        YamlConfiguration yml = new YamlConfiguration();
        Set<UUID> all = new HashSet<UUID>();
        all.addAll(lastNames.keySet());
        all.addAll(savedPlaytimeMs.keySet());
        all.addAll(lastSeenMs.keySet());
        all.addAll(nicknames.keySet());
        all.addAll(ignores.keySet());
        all.addAll(mailboxes.keySet());
        for (UUID id : all) {
            String path = "players." + id;
            if (lastNames.containsKey(id)) yml.set(path + ".name", lastNames.get(id));
            if (savedPlaytimeMs.containsKey(id)) yml.set(path + ".playtime-ms", savedPlaytimeMs.get(id));
            if (lastSeenMs.containsKey(id)) yml.set(path + ".last-seen", lastSeenMs.get(id));
            if (lastLocations.containsKey(id)) {
                LegacyCompat.writeLocation(yml.createSection(path + ".last-location"), lastLocations.get(id));
            }
            if (nicknames.containsKey(id)) yml.set(path + ".nick", nicknames.get(id));
            Set<UUID> ign = ignores.get(id);
            if (ign != null && !ign.isEmpty()) {
                List<String> str = new ArrayList<String>();
                for (UUID u : ign) str.add(u.toString());
                yml.set(path + ".ignores", str);
            }
            List<String> mail = mailboxes.get(id);
            if (mail != null && !mail.isEmpty()) {
                yml.set(path + ".mail", mail);
            }
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save player-info-legacy.yml: " + e.getMessage());
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

    private static List<String> filter(List<String> candidates, String prefix) {
        List<String> out = new ArrayList<String>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(c);
        }
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
