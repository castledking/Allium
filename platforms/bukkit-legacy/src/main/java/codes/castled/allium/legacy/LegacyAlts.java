package codes.castled.allium.legacy;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Alt detection on legacy Bukkit, as on the modern platform: every join records the player's IP,
 * and an account that first used an IP an older account already used is an alt. Alts get the
 * {@code alts.group} parent group (through LuckPerms' command, else Vault) unless exempted with
 * {@code /altexempt}, and holders of {@code allium.alerts} are told. The history lives in
 * alts-legacy.yml.
 */
final class LegacyAlts implements Listener {
    static final String FILE = "alts-legacy.yml";
    static final String PERMISSION_ALERTS = "allium.alerts";

    /** One account's use of one IP. */
    static final class Use {
        final String ip;
        final long firstSeen;
        long lastSeen;

        Use(String ip, long firstSeen, long lastSeen) {
            this.ip = ip;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }
    }

    static final class Exemption {
        final String actor;
        final String reason;
        final long at;

        Exemption(String actor, String reason, long at) {
            this.actor = actor;
            this.reason = reason;
            this.at = at;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final Function<String, OfflinePlayer> resolve;
    final Map<UUID, List<Use>> history = new LinkedHashMap<>();
    final Map<UUID, Exemption> exemptions = new HashMap<>();
    private boolean warnedNoBackend;
    /** The legacy runtime's config, which /allium reload refreshes. */
    java.util.function.Supplier<org.bukkit.configuration.Configuration> config;

    LegacyAlts(JavaPlugin plugin, LegacyMessageManager messages, Function<String, OfflinePlayer> resolve) {
        this.plugin = plugin;
        this.messages = messages;
        this.resolve = resolve;
        this.config = plugin::getConfig;
    }

    boolean enabled() {
        return config.get().getBoolean("modules.alts", true);
    }

    private ConfigurationSection settings() {
        ConfigurationSection section = config.get().getConfigurationSection("alts");
        return section == null ? new YamlConfiguration() : section;
    }

    String group() {
        String group = settings().getString("group", "alt");
        return group != null && group.matches("[A-Za-z0-9_.-]{1,64}") ? group : "alt";
    }

    void load() {
        history.clear();
        exemptions.clear();
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ips = yml.getConfigurationSection("history");
        if (ips != null) {
            for (String key : ips.getKeys(false)) {
                UUID id = uuid(key);
                if (id == null) continue;
                List<Use> uses = new ArrayList<>();
                for (String row : ips.getStringList(key)) {
                    String[] cells = row.split("\\|");
                    if (cells.length != 3) continue;
                    try {
                        uses.add(new Use(cells[0], Long.parseLong(cells[1]), Long.parseLong(cells[2])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                history.put(id, uses);
            }
        }
        ConfigurationSection exempt = yml.getConfigurationSection("exempt");
        if (exempt != null) {
            for (String key : exempt.getKeys(false)) {
                UUID id = uuid(key);
                if (id != null) exemptions.put(id, new Exemption(exempt.getString(key + ".actor", "?"),
                        exempt.getString(key + ".reason", ""), exempt.getLong(key + ".at")));
            }
        }
    }

    boolean save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, List<Use>> entry : history.entrySet()) {
            List<String> rows = new ArrayList<>();
            for (Use use : entry.getValue()) rows.add(use.ip + "|" + use.firstSeen + "|" + use.lastSeen);
            yml.set("history." + entry.getKey(), rows);
        }
        for (Map.Entry<UUID, Exemption> entry : exemptions.entrySet()) {
            String path = "exempt." + entry.getKey();
            yml.set(path + ".actor", entry.getValue().actor);
            yml.set(path + ".reason", entry.getValue().reason);
            yml.set(path + ".at", entry.getValue().at);
        }
        try {
            LegacyCompat.saveYamlAtomically(yml, new File(plugin.getDataFolder(), FILE));
            return true;
        } catch (IOException failed) {
            plugin.getLogger().warning("Could not save " + FILE + ": " + failed.getMessage());
            return false;
        }
    }

    /** Records that {@code id} joined from {@code ip}. */
    void record(UUID id, String ip, long now) {
        List<Use> uses = history.get(id);
        if (uses == null) history.put(id, uses = new ArrayList<>());
        for (Use use : uses) {
            if (use.ip.equals(ip)) {
                use.lastSeen = Math.max(use.lastSeen, now);
                return;
            }
        }
        uses.add(new Use(ip, now, now));
    }

    /** Whether another account used {@code ip} before {@code id} first did. */
    boolean isAlt(UUID id, String ip) {
        Use mine = use(id, ip);
        if (mine == null) return false;
        for (Map.Entry<UUID, List<Use>> other : history.entrySet()) {
            if (other.getKey().equals(id)) continue;
            for (Use use : other.getValue()) if (use.ip.equals(ip) && use.firstSeen < mine.firstSeen) return true;
        }
        return false;
    }

    /** Accounts sharing any IP with {@code id}, most recently seen first. */
    List<UUID> linked(UUID id, int limit) {
        List<Use> mine = history.get(id);
        if (mine == null) return Collections.emptyList();
        Map<UUID, Long> seen = new HashMap<>();
        for (Map.Entry<UUID, List<Use>> other : history.entrySet()) {
            if (other.getKey().equals(id)) continue;
            for (Use use : other.getValue()) {
                for (Use own : mine) {
                    if (own.ip.equals(use.ip)) {
                        Long known = seen.get(other.getKey());
                        if (known == null || known < use.lastSeen) seen.put(other.getKey(), use.lastSeen);
                    }
                }
            }
        }
        List<UUID> out = new ArrayList<>(seen.keySet());
        out.sort((a, b) -> Long.compare(seen.get(b), seen.get(a)));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private Use use(UUID id, String ip) {
        List<Use> uses = history.get(id);
        if (uses != null) for (Use use : uses) if (use.ip.equals(ip)) return use;
        return null;
    }

    /** The names of the accounts linked to {@code id}, as /seen and /whois list them. */
    List<String> linkedNames(UUID id) {
        List<String> names = new ArrayList<>();
        for (UUID other : linked(id, Math.max(1, Math.min(50, settings().getInt("max-listed", 10))))) {
            OfflinePlayer player = plugin.getServer().getOfflinePlayer(other);
            names.add(player != null && player.getName() != null ? player.getName() : other.toString().substring(0, 8));
        }
        return names;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        check(event.getPlayer());
    }

    void check(Player player) {
        if (!enabled()) return;
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return;
        // Behind a proxy without IP forwarding everyone shares a local address: that is no household.
        if (settings().getBoolean("ignore-local-addresses", true)
                && (address.getAddress().isLoopbackAddress() || address.getAddress().isAnyLocalAddress())) return;
        String ip = address.getAddress().getHostAddress();
        UUID id = player.getUniqueId();
        record(id, ip, System.currentTimeMillis());
        save();
        if (!isAlt(id, ip) || exemptions.containsKey(id)) return;
        String group = group();
        Assignment assignment = settings().getBoolean("assign-group", true) ? addGroup(player, group) : Assignment.UNAVAILABLE;
        List<String> linked = linkedNames(id);
        if (assignment == Assignment.ADDED) {
            plugin.getLogger().info("Alt detection: " + player.getName() + " shares an IP with " + String.join(", ", linked)
                    + "; added parent group " + group + ".");
        }
        // Staff hear about a new alt once, or on every join when no group marks the account.
        if (settings().getBoolean("notify-staff", true) && assignment != Assignment.ALREADY) {
            String accounts = linked.isEmpty() ? "?" : String.join(", ", linked);
            for (Player staff : plugin.getServer().getOnlinePlayers()) {
                if (staff != player && staff.hasPermission(PERMISSION_ALERTS)) {
                    messages.send(staff, "alts.detected",
                            "{prefix} &e{player} &cis an alt account &7(shares an IP with &f{accounts}&7); parent group &f{group}&7.",
                            "{player}", player.getName(), "{accounts}", accounts, "{group}", group);
                }
            }
        }
    }

    enum Assignment { ADDED, ALREADY, UNAVAILABLE }

    Assignment addGroup(Player player, String group) {
        if (player.hasPermission("group." + group)) return Assignment.ALREADY;
        if (plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            return plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    "lp user " + player.getUniqueId() + " parent add " + group) ? Assignment.ADDED : Assignment.UNAVAILABLE;
        }
        Object vault = vaultPermission();
        if (vault == null) {
            if (!warnedNoBackend) {
                warnedNoBackend = true;
                plugin.getLogger().warning("Alt detection: cannot add group " + group + " to " + player.getName()
                        + " (no LuckPerms and no Vault permission provider); staff are alerted instead. This is logged once.");
            }
            return Assignment.UNAVAILABLE;
        }
        try {
            Method add = vault.getClass().getMethod("playerAddGroup", String.class, OfflinePlayer.class, String.class);
            return Boolean.TRUE.equals(add.invoke(vault, null, player, group)) ? Assignment.ADDED : Assignment.UNAVAILABLE;
        } catch (ReflectiveOperationException | RuntimeException unsupported) {
            return Assignment.UNAVAILABLE;
        }
    }

    void removeGroup(OfflinePlayer target, String group) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "lp user " + target.getUniqueId() + " parent remove " + group);
            return;
        }
        Object vault = vaultPermission();
        if (vault == null) return;
        try {
            vault.getClass().getMethod("playerRemoveGroup", String.class, OfflinePlayer.class, String.class).invoke(vault, null, target, group);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private Object vaultPermission() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) return null;
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.permission.Permission");
            return plugin.getServer().getServicesManager().load(type);
        } catch (ClassNotFoundException | RuntimeException unavailable) {
            return null;
        }
    }

    /** {@code /altexempt <add|remove|check> <player> [reason]}. */
    boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.altexempt")) {
            messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2 || !Arrays.asList("add", "remove", "check").contains(action)) return false;
        OfflinePlayer target = resolve.apply(args[1]);
        if (target == null) {
            messages.send(sender, "info.seen-unknown", "{prefix} &cPlayer &f{player} &chas never joined.", "{player}", args[1]);
            return true;
        }
        String name = target.getName() != null ? target.getName() : args[1];
        switch (action) {
            case "add": {
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "No reason given";
                exemptions.put(target.getUniqueId(), new Exemption(sender instanceof Player ? sender.getName() : "Console",
                        reason, System.currentTimeMillis()));
                if (!save()) return unavailable(sender);
                removeGroup(target, group());
                messages.send(sender, "alts.exempt-added",
                        "{prefix} &aExempted &f{player} &afrom alt detection; the &f{group} &agroup was removed.",
                        "{player}", name, "{group}", group());
                return true;
            }
            case "remove":
                if (exemptions.remove(target.getUniqueId()) == null) {
                    messages.send(sender, "alts.exempt-none", "{prefix} &f{player} &cis not exempt from alt detection.", "{player}", name);
                    return true;
                }
                if (!save()) return unavailable(sender);
                messages.send(sender, "alts.exempt-removed",
                        "{prefix} &aRemoved &f{player}&a's alt exemption. It applies again on their next join.", "{player}", name);
                return true;
            default: {
                Exemption exemption = exemptions.get(target.getUniqueId());
                if (exemption == null) {
                    messages.send(sender, "alts.exempt-none", "{prefix} &f{player} &cis not exempt from alt detection.", "{player}", name);
                } else {
                    messages.send(sender, "alts.exempt-status",
                            "{prefix} &f{player} &ais exempt from alt detection &7(by &f{actor}&7: {reason}).",
                            "{player}", name, "{actor}", exemption.actor, "{reason}", exemption.reason);
                }
                return true;
            }
        }
    }

    private boolean unavailable(CommandSender sender) {
        messages.send(sender, "alts.unavailable", "{prefix} &cAlt account storage is unavailable right now. Try again shortly.");
        return true;
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        if (!sender.hasPermission("allium.altexempt")) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String option : Arrays.asList("add", "remove", "check")) if (option.startsWith(prefix)) out.add(option);
        } else if (args.length == 2) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(online.getName());
            }
        }
        return out;
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
