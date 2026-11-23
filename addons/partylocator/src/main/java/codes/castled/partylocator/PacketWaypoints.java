package codes.castled.partylocator;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.waypoint.EmptyWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.TrackedWaypoint;
import com.github.retrooper.packetevents.protocol.world.waypoint.Vec3iWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.WaypointIcon;
import com.github.retrooper.packetevents.util.Either;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The locator bar through PacketEvents, ported from legacy Allium. A hidden dot is removed by
 * cancelling vanilla's waypoint packets for that pair and untracking it, never with
 * {@code hidePlayer}, so the player stays in the tab list, in completion and visible to plugins
 * that check {@code canSee}. A shown dot is sent as the target's exact block position, which
 * also reaches party members vanilla would show only as a bearing or not at all; vanilla's own
 * updates for that pair are then cancelled until the dot is withdrawn.
 *
 * <p>Loaded only when PacketEvents is installed. Packet callbacks run on network threads, so all
 * state is concurrent.
 */
final class PacketWaypoints extends PacketListenerAbstract implements Waypoints {
    private final Logger logger;
    /** viewer -> targets whose dot is suppressed for them. */
    private final Map<UUID, Set<UUID>> hidden = new ConcurrentHashMap<>();
    /** viewer -> target -> the position the addon last sent, while it owns that dot. */
    private final Map<UUID, Map<UUID, Sent>> sent = new ConcurrentHashMap<>();
    /** The icon vanilla last gave each target (its colour and style), reused for positions sent here. */
    private final Map<UUID, WaypointIcon> icons = new ConcurrentHashMap<>();

    private record Sent(int x, int y, int z, WaypointIcon icon) {}

    PacketWaypoints(Logger logger) {
        super(PacketListenerPriority.NORMAL);
        this.logger = logger;
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.WAYPOINT) return;
        try {
            if (!(event.getPlayer() instanceof Player viewer)) return;
            WrapperPlayServerWaypoint packet = new WrapperPlayServerWaypoint(event);
            UUID target = targetOf(packet.getWaypoint());
            if (target == null) return;
            Map<UUID, Sent> owned = sent.get(viewer.getUniqueId());
            if (packet.getOperation() == WrapperPlayServerWaypoint.Operation.UNTRACK) {
                // Removals always reach the client; the next refresh sends the position again if
                // the dot should stay.
                if (owned != null) owned.remove(target);
                return;
            }
            icons.put(target, packet.getWaypoint().getIcon());
            Set<UUID> suppressed = hidden.get(viewer.getUniqueId());
            if ((suppressed != null && suppressed.contains(target)) || (owned != null && owned.containsKey(target))) {
                event.setCancelled(true);
            }
        } catch (RuntimeException | LinkageError failed) {
            logger.fine("Waypoint packet handling failed: " + failed);
        }
    }

    @Override
    public void apply(Player viewer, Player target, boolean show) {
        if (viewer.equals(target)) return;
        if (!show) {
            apply(viewer, target.getUniqueId(), 0, 0, 0, false);
            return;
        }
        Location at = target.getLocation();
        apply(viewer, target.getUniqueId(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), true);
    }

    @Override
    public void apply(Player viewer, UUID targetId, int x, int y, int z, boolean show) {
        UUID viewerId = viewer.getUniqueId();
        if (viewerId.equals(targetId)) return;
        Set<UUID> suppressed = hidden.computeIfAbsent(viewerId, key -> ConcurrentHashMap.newKeySet());
        Map<UUID, Sent> owned = sent.computeIfAbsent(viewerId, key -> new ConcurrentHashMap<>());
        if (!show) {
            boolean changed = suppressed.add(targetId);
            if (owned.remove(targetId) != null || changed) untrack(viewer, targetId);
            return;
        }
        suppressed.remove(targetId);
        Sent position = new Sent(x, y, z, icons.getOrDefault(targetId, defaultIcon()));
        if (same(owned.get(targetId), position)) return;
        // TRACK replaces an existing identifier too, including vanilla's bearing-only dot.
        if (send(viewer, WrapperPlayServerWaypoint.Operation.TRACK, targetId, position)) owned.put(targetId, position);
    }

    @Override
    public void clear(Player player) {
        UUID id = player.getUniqueId();
        hidden.remove(id);
        sent.remove(id);
        icons.remove(id);
        for (Map.Entry<UUID, Map<UUID, Sent>> entry : sent.entrySet()) {
            if (entry.getValue().remove(id) != null) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null && viewer.isOnline()) untrack(viewer, id);
            }
        }
        for (Set<UUID> targets : hidden.values()) targets.remove(id);
    }

    @Override
    public void shutdown() {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (RuntimeException | LinkageError ignored) {
        }
        for (Map.Entry<UUID, Map<UUID, Sent>> entry : sent.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()) continue;
            for (UUID target : entry.getValue().keySet()) untrack(viewer, target);
        }
        hidden.clear();
        sent.clear();
        icons.clear();
    }

    private void untrack(Player viewer, UUID target) {
        send(viewer, WrapperPlayServerWaypoint.Operation.UNTRACK, target, null);
    }

    private boolean send(Player viewer, WrapperPlayServerWaypoint.Operation operation, UUID target, Sent position) {
        try {
            TrackedWaypoint waypoint = position == null
                    ? new TrackedWaypoint(Either.createLeft(target), defaultIcon(), EmptyWaypointInfo.EMPTY)
                    : new TrackedWaypoint(Either.createLeft(target), position.icon(),
                            new Vec3iWaypointInfo(new Vector3i(position.x(), position.y(), position.z())));
            // Silently: the addon's own packets skip its listener, so they are never cancelled.
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, new WrapperPlayServerWaypoint(operation, waypoint));
            return true;
        } catch (RuntimeException | LinkageError failed) {
            logger.fine("Could not " + operation + " " + target + " for " + viewer.getName() + ": " + failed);
            return false;
        }
    }

    private static boolean same(Sent first, Sent second) {
        return first != null && first.x() == second.x() && first.y() == second.y() && first.z() == second.z()
                && Objects.equals(first.icon().getStyle(), second.icon().getStyle())
                && Objects.equals(first.icon().getColor(), second.icon().getColor());
    }

    private static WaypointIcon defaultIcon() {
        return new WaypointIcon(WaypointIcon.ICON_STYLE_DEFAULT, null);
    }

    private static UUID targetOf(TrackedWaypoint waypoint) {
        Either<UUID, String> id = waypoint == null ? null : waypoint.getIdentifier();
        return id != null && id.isLeft() ? id.getLeft() : null;
    }
}
