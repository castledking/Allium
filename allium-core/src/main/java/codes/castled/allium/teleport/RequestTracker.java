package codes.castled.allium.teleport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Main-thread-only pending requests, with one request per requester and a bounded total size.
 * Callers supply the current time; expired requests stay available to {@link #expire(long)} for
 * notification, but can never be selected by {@link #incoming(UUID, UUID, long)}.
 */
public final class RequestTracker {

    public enum Kind { TO_TARGET, HERE }

    public record Request(UUID requester, UUID recipient, Kind kind, long expiresAt) {
        public Request {
            Objects.requireNonNull(requester, "requester");
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(kind, "kind");
            if (requester.equals(recipient)) {
                throw new IllegalArgumentException("cannot request a teleport to yourself");
            }
        }
    }

    private final int maximum;
    private final Map<UUID, Request> requests = new LinkedHashMap<>();

    public RequestTracker() {
        this(4096);
    }

    public RequestTracker(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum pending requests must be positive");
        }
        this.maximum = maximum;
    }

    /**
     * Replaces this requester's previous request and makes this request the newest.
     * At capacity, a new requester is rejected without modifying any pending request.
     *
     * @throws IllegalStateException if a new requester would exceed the bound
     */
    public void put(Request request) {
        Objects.requireNonNull(request, "request");
        if (!requests.containsKey(request.requester()) && requests.size() >= maximum) {
            throw new IllegalStateException("maximum pending teleport requests reached");
        }
        requests.remove(request.requester());
        requests.put(request.requester(), request);
    }

    /** Finds an unexpired request; a null requester selects the most recently put request. */
    public Optional<Request> incoming(UUID recipient, UUID requester, long now) {
        Objects.requireNonNull(recipient, "recipient");
        if (requester != null) {
            Request request = requests.get(requester);
            return request != null && request.recipient().equals(recipient) && now < request.expiresAt()
                    ? Optional.of(request) : Optional.empty();
        }
        Request latest = null;
        for (Request request : requests.values()) {
            if (request.recipient().equals(recipient) && now < request.expiresAt()) {
                latest = request;
            }
        }
        return Optional.ofNullable(latest);
    }

    public int size() { return requests.size(); }

    public Optional<Request> remove(UUID requester) {
        return Optional.ofNullable(requests.remove(Objects.requireNonNull(requester, "requester")));
    }

    /** Cancels all requests addressed to this recipient, oldest first. */
    public List<Request> cancelIncoming(UUID recipient) {
        Objects.requireNonNull(recipient, "recipient");
        return removeMatching(request -> request.recipient().equals(recipient));
    }

    /** Cancels this player's outgoing request and all their incoming requests, oldest first. */
    public List<Request> cancelPlayer(UUID player) {
        Objects.requireNonNull(player, "player");
        return removeMatching(request -> request.requester().equals(player) || request.recipient().equals(player));
    }

    /** Removes requests at or after their deadline, oldest first. */
    public List<Request> expire(long now) {
        return removeMatching(request -> now >= request.expiresAt());
    }

    private List<Request> removeMatching(Predicate<Request> predicate) {
        List<Request> removed = new ArrayList<>();
        var iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            Request request = iterator.next();
            if (predicate.test(request)) {
                removed.add(request);
                iterator.remove();
            }
        }
        return List.copyOf(removed);
    }
}
