package codes.castled.allium.alts;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Alt detection (legacy Allium's IP history and alt group): every join records the player's IP.
 * An account that first joined from an IP an older account already used is an alt, and gets the
 * configured parent group ({@code alts.group}, {@code alt} by default) unless an admin exempted it
 * with {@code /altexempt}. {@code /seen} and {@code /whois} list the accounts linked by any shared
 * IP to staff with {@code allium.seen.alts} / {@code allium.whois.extra}.
 */
public final class AltService implements Listener {

    public static final String PERMISSION_EXEMPT = "allium.altexempt";
    public static final String PERMISSION_ALERTS = "allium.alerts";

    public record Settings(boolean assignGroup, String group, boolean notifyStaff, int maxListed, boolean ignoreLocal) {
        static Settings read(@Nullable ConfigurationSection c) {
            String group = c == null ? "alt" : c.getString("group", "alt");
            if (group == null || !group.matches("[A-Za-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("alts.group must be a plain group name");
            }
            return new Settings(c == null || c.getBoolean("assign-group", true), group,
                    c == null || c.getBoolean("notify-staff", true),
                    Math.max(1, Math.min(50, c == null ? 10 : c.getInt("max-listed", 10))),
                    c == null || c.getBoolean("ignore-local-addresses", true));
        }
    }

    /** What staff see about a player's linked accounts. */
    public record Summary(List<String> linkedNames, Optional<String> lastIp, boolean exempt) {
    }

    private final PluginStart plugin;
    private final AltRepository repository;
    private final PlayerNameRepository names;
    private final Executor io;
    private volatile Settings settings;
    private volatile boolean closed;
    private volatile boolean warnedNoBackend;

    public AltService(PluginStart plugin, AltRepository repository, PlayerNameRepository names, Executor io) {
        this.plugin = plugin;
        this.repository = repository;
        this.names = names;
        this.io = io;
        this.settings = Settings.read(plugin.getConfig().getConfigurationSection("alts"));
    }

    public void enable() throws SQLException {
        repository.migrate();
        plugin.getServer().getPluginManager().registerEvents(this, codes.castled.allium.PluginStart.bukkitPlugin(plugin));
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            check(online);
        }
    }

    public void disable() {
        closed = true;
        HandlerList.unregisterAll(this);
    }

    public boolean reloadSettings() {
        try {
            settings = Settings.read(plugin.getConfig().getConfigurationSection("alts"));
            return true;
        } catch (IllegalArgumentException invalid) {
            plugin.logger().warning("Invalid alts settings; previous settings kept: " + invalid.getMessage());
            return false;
        }
    }

    public Settings settings() {
        return settings;
    }

    public AltRepository repository() {
        return repository;
    }

    @Nullable
    static String ipOf(Player player) {
        InetSocketAddress address = player.getAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        check(event.getPlayer());
    }

    private void check(Player player) {
        String ip = ipOf(player);
        if (ip == null) {
            return;
        }
        // Behind a proxy without IP forwarding every player shares the proxy's loopback address:
        // treating that as one household would flag everyone after the first player as an alt.
        InetSocketAddress address = player.getAddress();
        if (settings.ignoreLocal() && address != null && address.getAddress() != null
                && (address.getAddress().isLoopbackAddress() || address.getAddress().isAnyLocalAddress())) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        execute(() -> {
            boolean alt;
            boolean exempt;
            List<String> linked;
            try {
                repository.record(uuid, ip, now);
                alt = repository.isAlt(uuid, ip);
                exempt = alt && repository.exemption(uuid).isPresent();
                linked = alt && !exempt ? linkedNames(uuid) : List.of();
            } catch (SQLException e) {
                plugin.logger().warning("Could not record " + player.getName() + "'s IP for alt detection: " + e.getMessage());
                return;
            }
            if (!alt || exempt) {
                return;
            }
            plugin.getScheduler().runOnServer(() -> {
                if (closed || !player.isOnline()) {
                    return;
                }
                Settings current = settings;
                Assignment assignment = current.assignGroup() ? addGroup(player, current.group()) : Assignment.UNAVAILABLE;
                if (assignment == Assignment.ADDED) {
                    plugin.logger().info("Alt detection: " + player.getName() + " shares an IP with "
                            + String.join(", ", linked) + "; added parent group " + current.group() + ".");
                }
                // Staff hear about a new alt once (when the group is added), or on every join when no
                // group can be assigned, since nothing else marks the account.
                if (current.notifyStaff() && assignment != Assignment.ALREADY) {
                    String accounts = linked.isEmpty() ? "?" : String.join(", ", linked);
                    String targetName = player.getName();
                    String group = current.group();
                    for (Player staff : plugin.getServer().getOnlinePlayers()) {
                        if (staff == player) continue;
                        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), staff, () -> {
                            if (!closed && staff.isOnline() && staff.hasPermission(PERMISSION_ALERTS)) {
                                plugin.getMessageManager().send(staff, Messages.ALTS_DETECTED, "{player}", targetName,
                                        "{accounts}", accounts, "{group}", group);
                            }
                        });
                    }
                }
            });
        });
    }

    private List<String> linkedNames(UUID uuid) throws SQLException {
        List<String> out = new ArrayList<>();
        for (UUID other : repository.linkedAccounts(uuid, settings.maxListed())) {
            out.add(nameOf(other));
        }
        return out;
    }

    private String nameOf(UUID uuid) throws SQLException {
        Optional<NameCache.Entry> cached = plugin.getPlayerNames() == null ? Optional.empty()
                : plugin.getPlayerNames().cache().byUuid(uuid);
        if (cached.isPresent()) {
            return cached.get().name();
        }
        return names.byUuid(uuid).map(NameCache.Entry::name).orElse(uuid.toString().substring(0, 8));
    }

    /** Linked accounts, last IP and exemption of {@code uuid}, read off the server thread. */
    public CompletableFuture<Summary> summary(UUID uuid) {
        CompletableFuture<Summary> result = new CompletableFuture<>();
        execute(() -> {
            try {
                result.complete(new Summary(linkedNames(uuid), repository.lastIp(uuid), repository.exemption(uuid).isPresent()));
            } catch (SQLException | RuntimeException e) {
                result.completeExceptionally(e);
            }
        }, result);
        return result;
    }

    /** Runs a blocking storage task on the pool; a full pool fails {@code onRejected}. */
    public void execute(Runnable task, CompletableFuture<?> onRejected) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException rejected) {
            onRejected.completeExceptionally(rejected);
        }
    }

    private void execute(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException rejected) {
            plugin.logger().warning("Alt detection queue is full; a join was not checked.");
        }
    }

    private boolean luckPerms() {
        var lp = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        return lp != null && lp.isEnabled();
    }

    enum Assignment { ADDED, ALREADY, UNAVAILABLE }

    /**
     * Adds {@code group} as a parent, globally (no world context). Through LuckPerms' own command
     * when LuckPerms is installed, so the change is logged and never world-scoped; else through
     * Vault. Server thread only.
     */
    Assignment addGroup(Player player, String group) {
        if (player.hasPermission("group." + group)) {
            return Assignment.ALREADY;
        }
        if (luckPerms()) {
            return plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    "lp user " + player.getUniqueId() + " parent add " + group) ? Assignment.ADDED : Assignment.UNAVAILABLE;
        }
        VaultGroups vault = VaultGroups.find(plugin.getServer());
        if (vault == null) {
            if (!warnedNoBackend) {
                warnedNoBackend = true;
                plugin.logger().warning("Alt detection: cannot add group " + group + " to " + player.getName()
                        + " (no LuckPerms and no Vault permission provider with group support); staff are alerted instead."
                        + " This is logged once.");
            }
            return Assignment.UNAVAILABLE;
        }
        if (vault.has(player, group)) {
            return Assignment.ALREADY;
        }
        return vault.add(player, group) ? Assignment.ADDED : Assignment.UNAVAILABLE;
    }

    /** Removes {@code group} from {@code target} (online or not). Server thread only. */
    public void removeGroup(OfflinePlayer target, String group) {
        if (luckPerms()) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    "lp user " + target.getUniqueId() + " parent remove " + group);
            return;
        }
        VaultGroups vault = VaultGroups.find(plugin.getServer());
        if (vault != null && vault.has(target, group)) {
            vault.remove(target, group);
        }
    }
}
