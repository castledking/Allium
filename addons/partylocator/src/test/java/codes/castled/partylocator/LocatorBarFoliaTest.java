package codes.castled.partylocator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LocatorBarFoliaTest {

    @Test
    void partyBookHandlesConcurrentRegionOperationsSafely() throws Exception {
        PartyBook book = new PartyBook();
        int threads = 8;
        var pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int idx = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 40; i++) {
                        UUID leader = UUID.randomUUID();
                        UUID member = UUID.randomUUID();
                        Party created = book.create("party-" + idx + "-" + i, leader);
                        assertNotNull(created);
                        assertEquals(PartyBook.Invite.SUCCESS, book.invite(leader, member));
                        assertEquals(PartyBook.Join.SUCCESS, book.join(created.name(), member));
                        assertTrue(book.sameParty(leader, member));
                        assertEquals(2, created.members().size());
                        assertTrue(book.leave(member));
                        assertFalse(book.sameParty(leader, member));
                        assertNotNull(book.disband(leader));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
            assertTrue(book.parties().isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }
}
