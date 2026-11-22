package codes.castled.partylocator;

import java.util.logging.Logger;

/**
 * Creates the PacketEvents waypoints. A class of its own so nothing that references PacketEvents
 * types is loaded, or verified, unless PacketEvents is installed.
 */
final class PacketSupport {
    private PacketSupport() {}

    static Waypoints create(Logger logger) {
        return new PacketWaypoints(logger);
    }
}
