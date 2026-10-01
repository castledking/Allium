package codes.castled.allium.packetevents.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.waypoint.EmptyWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.TrackedWaypoint;
import com.github.retrooper.packetevents.protocol.world.waypoint.WaypointIcon;
import com.github.retrooper.packetevents.util.Either;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.packetevents.LocatorVisibility;
import codes.castled.allium.util.SchedulerAdapter;

import static codes.castled.allium.managers.core.Text.DebugSeverity.WARN;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filters locator-bar waypoint packets per viewer, so a player can be removed from
 * someone's locator bar without {@code hidePlayer} and its tab list / canSee fallout.
 *
 * Loaded reflectively only when PacketEvents is installed.
 */
public class LocatorVisibilityPacketEventsImpl extends PacketListenerAbstract implements LocatorVisibility, Listener {

    private final PluginStart plugin;
    /** viewer -> targets whose dot is suppressed for them. */
    private final Map<UUID, Set<UUID>> hidden = new ConcurrentHashMap<>();
    /** Last waypoint the server sent for a target, reused to re-track them. */
    private final Map<UUID, TrackedWaypoint> lastWaypoint = new ConcurrentHashMap<>();

    public LocatorVisibilityPacketEventsImpl(PluginStart plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        SchedulerAdapter.runLater(() -> {
            try {
                PacketEvents.getAPI().getEventManager().registerListener(this);
            } catch (Throwable t) {
                Text.sendDebugLog(WARN, "Failed to register LocatorVisibility: " + t.getMessage());
            }
        }, 2L);
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.WAYPOINT) {
            return;
        }
        try {
            if (!(event.getPlayer() instanceof Player viewer)) {
                return;
            }
            WrapperPlayServerWaypoint packet = new WrapperPlayServerWaypoint(event);
            TrackedWaypoint waypoint = packet.getWaypoint();
            UUID targetId = targetOf(waypoint);
            if (targetId == null) {
                return;
            }
            // Remember the server's own waypoint so re-tracking uses the same icon/shape.
            if (packet.getOperation() != WrapperPlayServerWaypoint.Operation.UNTRACK) {
                lastWaypoint.put(targetId, waypoint);
            }
            if (isHidden(viewer.getUniqueId(), targetId)) {
                event.setCancelled(true);
            }
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "LocatorVisibility packet handling failed: " + t.getMessage());
        }
    }

    @Override
    public void setHidden(Player viewer, Player target, boolean hide) {
        if (viewer == null || target == null || viewer.equals(target)) {
            return;
        }
        UUID targetId = target.getUniqueId();
        Set<UUID> hiddenForViewer = hidden.computeIfAbsent(viewer.getUniqueId(), key -> ConcurrentHashMap.newKeySet());
        boolean changed = hide ? hiddenForViewer.add(targetId) : hiddenForViewer.remove(targetId);
        if (!changed) {
            return;
        }
        // The server believes the waypoint is still tracked either way, so the
        // transition packet has to come from us.
        send(viewer, hide ? WrapperPlayServerWaypoint.Operation.UNTRACK : WrapperPlayServerWaypoint.Operation.TRACK,
                targetId, target);
    }

    @Override
    public void clear(Player player) {
        UUID id = player.getUniqueId();
        hidden.remove(id);
        lastWaypoint.remove(id);
        for (Set<UUID> targets : hidden.values()) {
            targets.remove(id);
        }
    }

    @Override
    public void shutdown() {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Throwable ignored) {
        }
        HandlerList.unregisterAll(this);
        hidden.clear();
        lastWaypoint.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    private boolean isHidden(UUID viewerId, UUID targetId) {
        Set<UUID> targets = hidden.get(viewerId);
        return targets != null && targets.contains(targetId);
    }

    private void send(Player viewer, WrapperPlayServerWaypoint.Operation operation, UUID targetId, Player target) {
        try {
            TrackedWaypoint waypoint = operation == WrapperPlayServerWaypoint.Operation.UNTRACK
                    ? new TrackedWaypoint(Either.createLeft(targetId), defaultIcon(), EmptyWaypointInfo.EMPTY)
                    : trackedWaypointFor(targetId);
            PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(viewer, new WrapperPlayServerWaypoint(operation, waypoint));
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "LocatorVisibility could not " + operation + " " + target.getName()
                    + " for " + viewer.getName() + ": " + t.getMessage());
        }
    }

    private TrackedWaypoint trackedWaypointFor(UUID targetId) {
        TrackedWaypoint cached = lastWaypoint.get(targetId);
        if (cached != null) {
            return cached;
        }
        // Never saw one (e.g. hidden since before this player was tracked): an empty
        // waypoint still re-registers the id, and the server's next update fills it in.
        return new TrackedWaypoint(Either.createLeft(targetId), defaultIcon(), EmptyWaypointInfo.EMPTY);
    }

    private static WaypointIcon defaultIcon() {
        return new WaypointIcon(WaypointIcon.ICON_STYLE_DEFAULT, null);
    }

    private static UUID targetOf(TrackedWaypoint waypoint) {
        Either<UUID, String> identifier = waypoint == null ? null : waypoint.getIdentifier();
        return identifier != null && identifier.isLeft() ? identifier.getLeft() : null;
    }
}
