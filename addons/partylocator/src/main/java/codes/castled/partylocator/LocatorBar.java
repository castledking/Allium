package codes.castled.partylocator;

import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps every viewer's locator bar in step with {@link LocatorRules}. Every {@code refresh-ticks}
 * it decides each pair of online players and hands the result to {@link Waypoints}, which only
 * sends a packet when a dot appears, disappears or its target moved a block. Joins, world changes,
 * teleports, sneaking, game-mode changes and party changes recheck the players involved on the next
 * tick instead of waiting for the loop.
 */
final class LocatorBar implements Listener {
    static final String STAFF = "allium.party.staff";
    static final String SPECTATOR = "allium.gamemode.spectator";

    private record PlayerSnapshot(UUID worldId, double x, double y, double z, int blockX, int blockY, int blockZ,
                                  boolean concealed, boolean spectator, boolean npc) {}

    private final PartyLocator plugin;
    private final Waypoints waypoints;
    /** viewer -> target -> expiry in millis: positive shows until then, negative hides until minus it. */
    private final Map<UUID, Map<UUID, Long>> overrides = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> staffMode = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> worldBarEnabled = new ConcurrentHashMap<>();
    private final GameRule<Boolean> locatorBarRule = locatorBarRule();
    private volatile TaskHandle task;
    private volatile double radius;

    LocatorBar(PartyLocator plugin, Waypoints waypoints) {
        this.plugin = plugin;
        this.waypoints = waypoints;
    }

    /** The {@code locatorBar} game rule, or null before 1.21.6, where there is no locator bar. */
    @SuppressWarnings("unchecked")
    static GameRule<Boolean> locatorBarRule() {
        try {
            GameRule<?> rule = GameRule.getByName("locatorBar");
            return rule != null && rule.getType() == Boolean.class ? (GameRule<Boolean>) rule : null;
        } catch (RuntimeException | LinkageError absent) {
            return null;
        }
    }

    void start(double radius, long refreshTicks) {
        this.radius = radius;
        long period = Math.max(1, refreshTicks) * 50L;
        if (task != null) task.cancel();
        task = plugin.core().scheduler().repeatOnServer(() -> {
            refreshAll();
            return true;
        }, period, period);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        snapshots.clear();
        worldBarEnabled.clear();
        waypoints.shutdown();
    }

    double radius() {
        return radius;
    }

    void refreshAll() {
        if (plugin.core().isFolia()) {
            if (plugin.core().scheduler().isServerThread()) refreshWorldRules();
            else plugin.core().scheduler().runOnServer(this::refreshWorldRules);
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                plugin.core().runForEntity(player, () -> tickPlayer(player),
                        () -> snapshots.remove(player.getUniqueId()), 0L);
            }
            return;
        }
        List<Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        for (Player viewer : online) {
            for (Player target : online) {
                if (viewer != target) update(viewer, target);
            }
        }
    }

    /** Rechecks {@code player} against everyone, in both directions. */
    void refresh(Player player) {
        if (player == null || !player.isOnline()) return;
        if (plugin.core().isFolia()) {
            plugin.core().onOwner(player, () -> {
                if (!player.isOnline()) return;
                tickPlayer(player);
                for (Player other : plugin.getServer().getOnlinePlayers()) {
                    if (other == player) continue;
                    plugin.core().runForEntity(other, () -> updateSnapshotPair(other, player),
                            () -> snapshots.remove(other.getUniqueId()), 0L);
                }
            });
            return;
        }
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other == player) continue;
            update(player, other);
            update(other, player);
        }
    }

    void refreshNextTick(Player player) {
        if (plugin.core().isFolia()) {
            plugin.core().runForEntity(player, () -> refresh(player),
                    () -> snapshots.remove(player.getUniqueId()), 0L);
            return;
        }
        plugin.core().scheduler().runOnServer(() -> refresh(player));
    }

    private void refreshWorldRules() {
        for (World world : plugin.getServer().getWorlds()) {
            worldBarEnabled.put(world.getUID(), barEnabled(world));
        }
    }

    private PlayerSnapshot capture(Player player) {
        Location at = player.getLocation();
        PlayerSnapshot snapshot = new PlayerSnapshot(
                player.getWorld().getUID(),
                at.getX(), at.getY(), at.getZ(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                concealed(player),
                player.getGameMode() == GameMode.SPECTATOR,
                player.hasMetadata("NPC"));
        snapshots.put(player.getUniqueId(), snapshot);
        return snapshot;
    }

    private void tickPlayer(Player viewer) {
        if (!viewer.isOnline()) {
            snapshots.remove(viewer.getUniqueId());
            return;
        }
        PlayerSnapshot self = capture(viewer);
        boolean barOn = worldBarEnabled.getOrDefault(self.worldId(), true);
        boolean viewerStaff = staffMode(viewer);
        boolean viewerSpecPerm = viewer.hasPermission(SPECTATOR);
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (target == viewer || !target.isOnline()) continue;
            PlayerSnapshot targetSnap = snapshots.get(target.getUniqueId());
            if (targetSnap == null && plugin.core().ownsEntity(target)) targetSnap = capture(target);
            if (targetSnap == null || targetSnap.npc()) continue;
            applySnapshotPair(viewer, self, barOn, viewerStaff, viewerSpecPerm, target, targetSnap);
        }
    }

    private void updateSnapshotPair(Player viewer, Player target) {
        if (!viewer.isOnline() || !target.isOnline() || viewer == target) return;
        PlayerSnapshot self = capture(viewer);
        PlayerSnapshot targetSnap = snapshots.get(target.getUniqueId());
        if (targetSnap == null && plugin.core().ownsEntity(target)) targetSnap = capture(target);
        if (targetSnap == null || targetSnap.npc()) return;
        applySnapshotPair(viewer, self, worldBarEnabled.getOrDefault(self.worldId(), true),
                staffMode(viewer), viewer.hasPermission(SPECTATOR), target, targetSnap);
    }

    private void applySnapshotPair(Player viewer, PlayerSnapshot self, boolean barOn, boolean viewerStaff,
                                   boolean viewerSpecPerm, Player target, PlayerSnapshot targetSnap) {
        boolean sameWorld = self.worldId().equals(targetSnap.worldId());
        double distSq = Double.MAX_VALUE;
        if (sameWorld) {
            double dx = self.x() - targetSnap.x();
            double dy = self.y() - targetSnap.y();
            double dz = self.z() - targetSnap.z();
            distSq = dx * dx + dy * dy + dz * dz;
        }
        LocatorRules.Pair pair = new LocatorRules.Pair(
                sameWorld, barOn,
                plugin.core().hiddenFrom(viewer, target),
                targetSnap.concealed(),
                plugin.book().sameParty(viewer.getUniqueId(), target.getUniqueId()),
                override(viewer.getUniqueId(), target.getUniqueId()),
                viewerStaff, self.spectator(), viewerSpecPerm, targetSnap.spectator(),
                distSq, radius);
        waypoints.apply(viewer, target.getUniqueId(), targetSnap.blockX(), targetSnap.blockY(), targetSnap.blockZ(),
                LocatorRules.show(pair));
    }

    private void update(Player viewer, Player target) {
        if (!viewer.isOnline() || !target.isOnline()) return;
        // Citizens NPCs keep vanilla's dot, as in legacy.
        if (target.hasMetadata("NPC")) return;
        waypoints.apply(viewer, target, LocatorRules.show(pair(viewer, target)));
    }

    LocatorRules.Pair pair(Player viewer, Player target) {
        boolean sameWorld = viewer.getWorld().equals(target.getWorld());
        return new LocatorRules.Pair(sameWorld, barEnabled(viewer.getWorld()),
                plugin.core().hiddenFrom(viewer, target), concealed(target),
                plugin.book().sameParty(viewer.getUniqueId(), target.getUniqueId()),
                override(viewer.getUniqueId(), target.getUniqueId()), staffMode(viewer),
                viewer.getGameMode() == GameMode.SPECTATOR, viewer.hasPermission(SPECTATOR),
                target.getGameMode() == GameMode.SPECTATOR,
                sameWorld ? viewer.getLocation().distanceSquared(target.getLocation()) : Double.MAX_VALUE, radius);
    }

    private boolean barEnabled(World world) {
        return locatorBarRule == null || !Boolean.FALSE.equals(world.getGameRuleValue(locatorBarRule));
    }

    /** Vanilla's ways of hiding a dot: sneaking, invisibility, a carved pumpkin or a mob head. */
    private static boolean concealed(Player target) {
        if (target.isSneaking() || target.isInvisible()) return true;
        ItemStack helmet = target.getInventory().getHelmet();
        if (helmet == null) return false;
        Material type = helmet.getType();
        return type == Material.CARVED_PUMPKIN || type.name().endsWith("_HEAD") || type.name().endsWith("_SKULL");
    }

    boolean staffMode(Player viewer) {
        return staffMode.getOrDefault(viewer.getUniqueId(), viewer.hasPermission(STAFF));
    }

    /** Flips staff locator mode, returning the new state. */
    boolean toggleStaffMode(Player viewer) {
        boolean next = !staffMode(viewer);
        staffMode.put(viewer.getUniqueId(), next);
        refresh(viewer);
        return next;
    }

    /** Forces {@code target} on or off {@code viewer}'s bar for {@code seconds}. */
    void force(Player viewer, Player target, long seconds, boolean show) {
        long until = System.currentTimeMillis() + seconds * 1000L;
        overrides.computeIfAbsent(viewer.getUniqueId(), key -> new ConcurrentHashMap<>()).put(target.getUniqueId(), show ? until : -until);
        if (plugin.core().isFolia()) plugin.core().onOwner(viewer, () -> updateSnapshotPair(viewer, target));
        else update(viewer, target);
    }

    void reset(Player viewer, Player target) {
        Map<UUID, Long> forViewer = overrides.get(viewer.getUniqueId());
        if (forViewer != null) forViewer.remove(target.getUniqueId());
        if (plugin.core().isFolia()) plugin.core().onOwner(viewer, () -> updateSnapshotPair(viewer, target));
        else update(viewer, target);
    }

    private int override(UUID viewer, UUID target) {
        Map<UUID, Long> forViewer = overrides.get(viewer);
        Long until = forViewer == null ? null : forViewer.get(target);
        if (until == null) return 0;
        if (System.currentTimeMillis() < Math.abs(until)) return until > 0 ? 1 : -1;
        forViewer.remove(target);
        return 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        refreshNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        snapshots.remove(id);
        waypoints.clear(event.getPlayer());
        overrides.remove(id);
        for (Map<UUID, Long> forViewer : overrides.values()) forViewer.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void changedWorld(PlayerChangedWorldEvent event) {
        // A dimension change resets the client's waypoints, so every dot is sent again.
        waypoints.clear(event.getPlayer());
        refreshNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        refreshNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) {
        refreshNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent event) {
        refreshNextTick(event.getPlayer());
    }
}
