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
 * Pending requests shared by player regions, with one request per requester and a bounded total size.
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

    private record Key(UUID requester, UUID recipient) {}

    private final int maximum;
    private final Map<Key, Request> requests = new LinkedHashMap<>();

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
     * Replaces this requester's previous request(s) and makes this request the newest.
     * At capacity, a new requester is rejected without modifying any pending request.
     *
     * @throws IllegalStateException if a new requester would exceed the bound
     */
    public synchronized void put(Request request) {
        Objects.requireNonNull(request, "request");
        boolean hasExisting = requests.keySet().stream().anyMatch(k -> k.requester().equals(request.requester()));
        if (!hasExisting && requests.size() >= maximum) {
            throw new IllegalStateException("maximum pending teleport requests reached");
        }
        requests.keySet().removeIf(k -> k.requester().equals(request.requester()));
        requests.put(new Key(request.requester(), request.recipient()), request);
    }

    /**
     * Adds or replaces a single {@code (requester, recipient)} request without clearing requests
     * addressed to other recipients (used by {@code /tpaall}).
     *
     * @throws IllegalStateException if adding a new pair would exceed the bound
     */
    public synchronized void putAdditional(Request request) {
        Objects.requireNonNull(request, "request");
        Key key = new Key(request.requester(), request.recipient());
        if (!requests.containsKey(key) && requests.size() >= maximum) {
            throw new IllegalStateException("maximum pending teleport requests reached");
        }
        requests.remove(key);
        requests.put(key, request);
    }

    /** Finds an unexpired request; a null requester selects the most recently put request. */
    public synchronized Optional<Request> incoming(UUID recipient, UUID requester, long now) {
        Objects.requireNonNull(recipient, "recipient");
        if (requester != null) {
            Request request = requests.get(new Key(requester, recipient));
            return request != null && now < request.expiresAt()
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

    public synchronized int size() { return requests.size(); }

    /** Unexpired requests {@code player} sent or received. */
    public synchronized int involving(UUID player, long now) {
        int count = 0;
        for (Request request : requests.values()) {
            if (now < request.expiresAt() && (request.requester().equals(player) || request.recipient().equals(player))) {
                count++;
            }
        }
        return count;
    }

    /** Removes all pending requests sent by {@code requester}, returning the newest removed one if any. */
    public synchronized Optional<Request> remove(UUID requester) {
        Objects.requireNonNull(requester, "requester");
        List<Request> removed = removeMatching(request -> request.requester().equals(requester));
        return removed.isEmpty() ? Optional.empty() : Optional.of(removed.get(removed.size() - 1));
    }

    /** Removes one specific pending request between {@code requester} and {@code recipient}. */
    public synchronized Optional<Request> remove(UUID requester, UUID recipient) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(recipient, "recipient");
        return Optional.ofNullable(requests.remove(new Key(requester, recipient)));
    }

    /** Removes matching pending requests without notification and returns the removed entries. */
    public synchronized List<Request> removeIf(Predicate<Request> predicate) {
        return removeMatching(Objects.requireNonNull(predicate, "predicate"));
    }

    /** Cancels all requests addressed to this recipient, oldest first. */
    public synchronized List<Request> cancelIncoming(UUID recipient) {
        Objects.requireNonNull(recipient, "recipient");
        return removeMatching(request -> request.recipient().equals(recipient));
    }

    /** Cancels this player's outgoing request and all their incoming requests, oldest first. */
    public synchronized List<Request> cancelPlayer(UUID player) {
        Objects.requireNonNull(player, "player");
        return removeMatching(request -> request.requester().equals(player) || request.recipient().equals(player));
    }

    /** Removes requests at or after their deadline, oldest first. */
    public synchronized List<Request> expire(long now) {
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
