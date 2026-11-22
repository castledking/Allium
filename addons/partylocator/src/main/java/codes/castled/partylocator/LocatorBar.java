package codes.castled.partylocator;

import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

import java.util.*;

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

    private final PartyLocator plugin;
    private final Waypoints waypoints;
    /** viewer -> target -> expiry in millis: positive shows until then, negative hides until minus it. */
    private final Map<UUID, Map<UUID, Long>> overrides = new HashMap<>();
    private final Map<UUID, Boolean> staffMode = new HashMap<>();
    private final GameRule<Boolean> locatorBarRule = locatorBarRule();
    private TaskHandle task;
    private double radius;

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
        waypoints.shutdown();
    }

    double radius() {
        return radius;
    }

    void refreshAll() {
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
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other == player) continue;
            update(player, other);
            update(other, player);
        }
    }

    void refreshNextTick(Player player) {
        plugin.core().scheduler().runOnServer(() -> refresh(player));
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
        overrides.computeIfAbsent(viewer.getUniqueId(), key -> new HashMap<>()).put(target.getUniqueId(), show ? until : -until);
        update(viewer, target);
    }

    void reset(Player viewer, Player target) {
        Map<UUID, Long> forViewer = overrides.get(viewer.getUniqueId());
        if (forViewer != null) forViewer.remove(target.getUniqueId());
        update(viewer, target);
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
        waypoints.clear(event.getPlayer());
        overrides.remove(event.getPlayer().getUniqueId());
        for (Map<UUID, Long> forViewer : overrides.values()) forViewer.remove(event.getPlayer().getUniqueId());
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
