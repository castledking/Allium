package codes.castled.allium.portable;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** In-memory TPA request and /back position tracking shared across native mod adapters. */
public final class TeleportRequests {
    public static final long DEFAULT_EXPIRY_MILLIS = 60_000L;

    public static final class Request {
        public final UUID sender;
        public final UUID target;
        public final boolean here;
        public final long expiresAt;

        public Request(UUID sender, UUID target, boolean here, long expiresAt) {
            this.sender = sender;
            this.target = target;
            this.here = here;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<UUID, Request> incomingByTarget = new ConcurrentHashMap<UUID, Request>();
    private final Map<UUID, Homes.Position> backByPlayer = new ConcurrentHashMap<UUID, Homes.Position>();
    private final LongSupplier clock;
    private final long expiryMillis;

    public TeleportRequests() {
        this(System::currentTimeMillis, DEFAULT_EXPIRY_MILLIS);
    }

    public TeleportRequests(LongSupplier clock, long expiryMillis) {
        this.clock = clock;
        this.expiryMillis = expiryMillis;
    }

    public void recordBack(UUID player, Homes.Position position) {
        if (player != null && position != null) {
            backByPlayer.put(player, position);
        }
    }

    public Homes.Position backOf(UUID player) {
        return player == null ? null : backByPlayer.get(player);
    }

    public Request create(UUID sender, UUID target, boolean here) {
        Request req = new Request(sender, target, here, clock.getAsLong() + expiryMillis);
        incomingByTarget.put(target, req);
        return req;
    }

    public Request consumeIncoming(UUID target) {
        Request req = incomingByTarget.remove(target);
        if (req == null || req.expiresAt < clock.getAsLong()) {
            return null;
        }
        return req;
    }

    public UUID cancelOutgoing(UUID sender) {
        long now = clock.getAsLong();
        Iterator<Map.Entry<UUID, Request>> it = incomingByTarget.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Request> entry = it.next();
            Request req = entry.getValue();
            if (req.expiresAt < now) {
                it.remove();
            } else if (req.sender.equals(sender)) {
                it.remove();
                return req.target;
            }
        }
        return null;
    }
}
