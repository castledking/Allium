package codes.castled.allium.packetevents;

import org.bukkit.entity.Player;

/** Used when PacketEvents is absent or packet filtering is disabled. */
public final class LocatorVisibilityNoOp implements LocatorVisibility {

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public void setHidden(Player viewer, Player target, boolean hidden) {
    }

    @Override
    public void clear(Player player) {
    }

    @Override
    public void shutdown() {
    }
}
