package codes.castled.allium.naming;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Bounded, thread-safe memory index shared by mail and payments. A UUID has one current name,
 * and a case-insensitive name has one owner. Delayed storage reads cannot replace newer entries.
 * Eviction uses last-seen time, rather than the order in which asynchronous loads finish.
 */
public final class NameCache {

    public record Entry(UUID uuid, String name, long lastSeen) {
        public Entry {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                throw new IllegalArgumentException("invalid player name");
            }
        }
    }

    private final int capacity;
    private final Map<UUID, Entry> byUuid = new HashMap<>();
    private final Map<String, Entry> byName = new HashMap<>();
    private final NavigableSet<Entry> bySeen = new TreeSet<>(Comparator.comparingLong(Entry::lastSeen)
            .thenComparing(Entry::uuid));

    public NameCache(int capacity) {
        if (capacity < 1 || capacity > 1_000_000) {
            throw new IllegalArgumentException("name cache capacity must be from 1 to 1000000");
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    /** Retains only the newest observation of a UUID and name. Equal timestamps prefer this call. */
    public synchronized void put(UUID uuid, String name, long seen) {
        Entry incoming = new Entry(uuid, name, seen);
        Entry previous = byUuid.get(uuid);
        Entry owner = byName.get(key(name));
        if ((previous != null && previous.lastSeen() > seen) || (owner != null && owner.lastSeen() > seen)) {
            return;
        }
        remove(previous);
        remove(owner);
        byUuid.put(uuid, incoming);
        byName.put(key(name), incoming);
        bySeen.add(incoming);
        while (byUuid.size() > capacity) {
            remove(bySeen.first());
        }
    }

    private void remove(Entry entry) {
        if (entry != null) {
            byUuid.remove(entry.uuid(), entry);
            byName.remove(key(entry.name()), entry);
            bySeen.remove(entry);
        }
    }

    public synchronized Optional<Entry> byName(String name) {
        return Optional.ofNullable(byName.get(key(name)));
    }

    public synchronized Optional<Entry> byUuid(UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    /** Up to {@code limit} matching names, newest first, with no storage or offline-player access. */
    public List<String> complete(String prefix, int limit) {
        return complete(prefix, limit, entry -> true);
    }

    /**
     * Filters before applying the limit so hidden entries cannot crowd out visible suggestions.
     * The predicate must be a short memory-only check against a request's visibility snapshot.
     */
    public synchronized List<String> complete(String prefix, int limit, Predicate<Entry> visible) {
        if (limit <= 0) {
            return List.of();
        }
        String p = key(prefix);
        List<String> out = new ArrayList<>(Math.min(Math.min(limit, capacity), 50));
        for (var entries = bySeen.descendingIterator(); entries.hasNext() && out.size() < limit;) {
            Entry entry = entries.next();
            if (key(entry.name()).startsWith(p) && visible.test(entry)) {
                out.add(entry.name());
            }
        }
        return List.copyOf(out);
    }

    /** Removes an obsolete observation only if no newer observation has replaced it. */
    public synchronized void invalidate(Entry observed) {
        if (observed != null && observed.equals(byUuid.get(observed.uuid()))) {
            remove(observed);
        }
    }

    public synchronized int size() {
        return byUuid.size();
    }

    public synchronized void clear() {
        byUuid.clear();
        byName.clear();
        bySeen.clear();
    }

    private static String key(String name) {
        return Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
    }
}
