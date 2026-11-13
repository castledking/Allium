package codes.castled.allium.naming;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NameCacheTest {

    @Test
    void boundedMostRecentFirstAndCaseInsensitive() {
        NameCache cache = new NameCache(3);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        cache.put(a, "Alice", 1);
        cache.put(b, "Alfred", 2);
        cache.put(c, "Bob", 3);
        assertEquals(List.of("Alfred", "Alice"), cache.complete("al", 10), "most recently seen first");
        cache.put(a, "Alice", 4);
        assertEquals(List.of("Alice", "Alfred"), cache.complete("AL", 10));
        cache.put(d, "Dave", 5);
        assertEquals(3, cache.size());
        assertTrue(cache.byUuid(b).isEmpty(), "the least recently seen was dropped");
        assertEquals(List.of("Alice"), cache.complete("al", 10));
        assertEquals(List.of("Alice"), cache.complete("a", 1), "the limit holds");
    }

    @Test
    void aRenameAndAReusedNameResolveToTheCurrentHolder() {
        NameCache cache = new NameCache(10);
        UUID old = UUID.randomUUID(), now = UUID.randomUUID();
        cache.put(old, "Steve", 1);
        cache.put(old, "Stevie", 2);
        assertTrue(cache.byName("Steve").isEmpty(), "the old name is gone with the rename");
        cache.put(now, "Steve", 3);
        assertEquals(now, cache.byName("steve").orElseThrow().uuid());
        assertEquals("Stevie", cache.byUuid(old).orElseThrow().name());
        assertThrows(IllegalArgumentException.class, () -> new NameCache(0));
    }
    @Test
    void delayedLoadsCannotUndoRenamesOrDisplaceRecentPlayers() {
        NameCache cache = new NameCache(2);
        UUID renamed = UUID.randomUUID(), recent = UUID.randomUUID();
        cache.put(renamed, "Current", 20);
        cache.put(recent, "Recent", 30);
        cache.put(renamed, "Obsolete", 10);
        cache.put(UUID.randomUUID(), "OldStartupRow", 1);
        assertEquals(List.of("Recent", "Current"), cache.complete("", 50));
        assertTrue(cache.byName("Obsolete").isEmpty());
        assertEquals(20, cache.byUuid(renamed).orElseThrow().lastSeen());
    }

    @Test
    void reusedNamesHaveOnlyOneOwnerEvenWhenOldRowsArriveLate() {
        NameCache cache = new NameCache(10);
        UUID old = UUID.randomUUID(), current = UUID.randomUUID();
        cache.put(old, "Name", 10);
        cache.put(current, "NAME", 30);
        cache.put(old, "Name", 20);
        assertEquals(current, cache.byName("name").orElseThrow().uuid());
        assertEquals(List.of("NAME"), cache.complete("n", 50));
        assertTrue(cache.byUuid(old).isEmpty(), "a reused name must not retain its obsolete owner");
    }

    @Test
    void limitsAreBoundedAndInvalidNamesCannotEnterTheIndex() {
        NameCache cache = new NameCache(2);
        assertThrows(IllegalArgumentException.class, () -> cache.put(UUID.randomUUID(), "x".repeat(17), 1));
        assertThrows(IllegalArgumentException.class, () -> cache.put(UUID.randomUUID(), "", 1));
        cache.put(UUID.randomUUID(), "Alice", 1);
        assertEquals(List.of(), cache.complete("", 0));
        assertEquals(List.of(), cache.complete("", -1));
        assertEquals(List.of("Alice"), cache.complete("", Integer.MAX_VALUE));
    }

    @Test
    void concurrentLoadsAndCompletionsKeepTheIndexConsistentAndBounded() throws Exception {
        NameCache cache = new NameCache(32);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Callable<Void>> jobs = new java.util.ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                int w = worker;
                jobs.add(() -> {
                    for (int i = 0; i < 500; i++) {
                        cache.put(new UUID(w, i % 100), "P" + w + "_" + i, i);
                        List<String> names = cache.complete("p", 8);
                        assertTrue(names.size() <= 8);
                        assertEquals(names.size(), names.stream().map(n -> n.toLowerCase(java.util.Locale.ROOT)).distinct().count());
                    }
                    return null;
                });
            }
            for (var result : pool.invokeAll(jobs)) {
                result.get();
            }
            assertTrue(cache.size() <= 32);
        } finally {
            pool.shutdownNow();
        }
    }

}
