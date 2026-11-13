package codes.castled.allium.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The send limits and the claim state machine, on SQLite (and MySQL when configured). */
class MailRepositoryTest {

    @TempDir
    Path dir;

    private static final MailLimits LIMITS = new MailLimits(3, 2, 60_000L, 256, 3_600_000L, 64);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static MailRepository repository(MailDatabases.Db db) throws Exception {
        MailRepository repository = new MailRepository(db.source(), db.dialect(), System::currentTimeMillis);
        assertEquals(2, repository.migrate(), db.name());
        assertEquals(2, repository.migrate(), db.name() + ": migrating again changes nothing");
        return repository;
    }

    private static MailRecord text(UUID from, UUID to, long now) {
        return MailRecord.text(UUID.randomUUID(), to, from, "name", "hello", "server-a", now, LIMITS.expiresAt(now));
    }

    private static MailRecord gift(UUID from, UUID to, long now) {
        return MailRecord.pendingGift(UUID.randomUUID(), to, from, "name", "a note", "items-v1", 2, "server-a", now);
    }

    @Test
    void theMailboxSizeAndTheSendRateAreEnforcedInTheSameTransaction() throws Exception {
        for (MailDatabases.Db db : MailDatabases.all(dir)) {
            MailRepository repo = repository(db);
            long now = System.currentTimeMillis();
            assertEquals(MailRepository.SendResult.SAVED, repo.send(text(ALICE, BOB, now), LIMITS, false), db.name());
            assertEquals(MailRepository.SendResult.SAVED, repo.send(text(ALICE, BOB, now + 1), LIMITS, false), db.name());
            assertEquals(MailRepository.SendResult.RATE_LIMITED, repo.send(text(ALICE, BOB, now + 2), LIMITS, false),
                    db.name() + ": Alice's third send in the window");
            assertEquals(MailRepository.SendResult.SAVED, repo.send(text(CAROL, BOB, now + 3), LIMITS, false), db.name());
            assertEquals(MailRepository.SendResult.MAILBOX_FULL, repo.send(text(CAROL, BOB, now + 4), LIMITS, false),
                    db.name() + ": Bob's mailbox holds 3");
            assertEquals(MailRepository.SendResult.SAVED, repo.send(text(CAROL, BOB, now + 5), LIMITS, true),
                    db.name() + ": the bypass permission skips both limits");
            assertEquals(MailRepository.SendResult.SAVED, repo.send(text(ALICE, CAROL, now + 61_000L), LIMITS, false),
                    db.name() + ": the window moved on");
            assertEquals(4, repo.mailbox(BOB, now + 10).size(), db.name());
            assertEquals(4, repo.unread(BOB, now + 10), db.name());
        }
    }

    @Test
    void concurrentSendsToOneMailboxNeverExceedItsSize() throws Exception {
        for (MailDatabases.Db db : MailDatabases.all(dir)) {
            MailRepository repo = repository(db);
            long now = System.currentTimeMillis();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger saved = new AtomicInteger();
            List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                UUID sender = UUID.randomUUID();
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        if (repo.send(text(sender, BOB, now), LIMITS, false) == MailRepository.SendResult.SAVED) {
                            saved.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.add(e);
                    }
                });
                threads.add(t);
                t.start();
            }
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }
            assertEquals(List.of(), errors, db.name() + ": no send failed (lock conflicts are retried)");
            assertEquals(LIMITS.mailboxSize(), saved.get(), db.name() + ": exactly the mailbox size was stored");
            assertEquals(LIMITS.mailboxSize(), repo.mailbox(BOB, now + 1).size(), db.name());
        }
    }

    @Test
    void aPendingGiftIsInvisibleUntilActivatedAndCancellingRefundsItsRateSlot() throws Exception {
        for (MailDatabases.Db db : MailDatabases.all(dir)) {
            MailRepository repo = repository(db);
            long now = System.currentTimeMillis();
            MailRecord first = gift(ALICE, BOB, now);
            assertEquals(MailRepository.SendResult.SAVED, repo.send(first, LIMITS, false));
            assertTrue(repo.mailbox(BOB, now).isEmpty(), db.name() + ": PENDING is never shown");
            assertEquals(List.of(first.id()), repo.pendingFrom(ALICE, "server-a").stream().map(MailRecord::id).toList());
            assertTrue(repo.pendingFrom(ALICE, "server-b").isEmpty(), db.name() + ": recovery is the origin server's");
            assertTrue(repo.activate(first.id()));
            assertTrue(repo.activate(first.id()), db.name() + ": activating twice acknowledges the original commit");
            assertEquals(MailState.READY, repo.mailbox(BOB, now).get(0).state());

            MailRecord second = gift(ALICE, BOB, now + 1);
            assertEquals(MailRepository.SendResult.SAVED, repo.send(second, LIMITS, false));
            assertEquals(MailRepository.SendResult.RATE_LIMITED, repo.send(gift(ALICE, BOB, now + 2), LIMITS, false));
            assertTrue(repo.cancelPending(second.id()));
            assertFalse(repo.cancelPending(first.id()), db.name() + ": a READY gift is never cancelled");
            assertEquals(MailRepository.SendResult.SAVED, repo.send(gift(ALICE, CAROL, now + 3), LIMITS, false),
                    db.name() + ": the cancelled gift's rate slot was refunded");
        }
    }

    @Test
    void aClaimIsWonOnceFinishedOnlyUnderItsTokenAndKeepsWhatDidNotFit() throws Exception {
        for (MailDatabases.Db db : MailDatabases.all(dir)) {
            MailRepository repo = repository(db);
            long now = System.currentTimeMillis();
            MailRecord gift = gift(ALICE, BOB, now);
            repo.send(gift, LIMITS, false);
            UUID early = UUID.randomUUID();
            assertFalse(repo.beginClaim(gift.id(), BOB, early, "server-a", now), db.name() + ": a PENDING gift cannot be claimed");
            repo.activate(gift.id());
            assertFalse(repo.beginClaim(gift.id(), CAROL, early, "server-a", now), db.name() + ": only the recipient claims");

            UUID token = UUID.randomUUID();
            UUID rival = UUID.randomUUID();
            assertTrue(repo.beginClaim(gift.id(), BOB, token, "server-a", now));
            assertFalse(repo.beginClaim(gift.id(), BOB, rival, "server-b", now), db.name() + ": the second claimer loses");
            assertEquals(List.of(gift.id()), repo.claimingFor(BOB, "server-a").stream().map(MailRecord::id).toList());
            assertTrue(repo.claimingFor(BOB, "server-b").isEmpty());
            assertFalse(repo.finishClaim(gift.id(), rival, null, 0), db.name() + ": the losing token finishes nothing");
            assertFalse(repo.releaseClaim(gift.id(), rival), db.name() + ": nor releases it");

            assertTrue(repo.finishClaim(gift.id(), token, "items-rest", 1), db.name() + ": what did not fit stays");
            MailRecord rest = repo.find(gift.id()).orElseThrow();
            assertEquals(MailState.READY, rest.state());
            assertEquals("items-rest", rest.items());
            assertEquals(1, rest.itemCount());
            assertNull(rest.claimToken());
            assertTrue(repo.finishClaim(gift.id(), token, null, 0), db.name() + ": replay acknowledges but does not consume the remnant");
            assertEquals("items-rest", repo.find(gift.id()).orElseThrow().items());

            UUID second = UUID.randomUUID();
            assertTrue(repo.beginClaim(gift.id(), BOB, second, "server-a", now));
            assertTrue(repo.releaseClaim(gift.id(), second), db.name() + ": a claim that delivered nothing is released");
            assertEquals("items-rest", repo.find(gift.id()).orElseThrow().items(), db.name() + ": unchanged by the release");
            UUID third = UUID.randomUUID();
            assertTrue(repo.beginClaim(gift.id(), BOB, third, "server-a", now));
            assertTrue(repo.finishClaim(gift.id(), third, null, 0));
            assertTrue(repo.find(gift.id()).isEmpty(), db.name() + ": a fully claimed gift is gone");
        }
    }

    @Test
    void ambiguousAcknowledgementsStayProvableAfterTheGiftRowDisappears() throws Exception {
        for (MailDatabases.Db db : MailDatabases.all(dir)) {
            MailRepository repo = repository(db);
            long now = System.currentTimeMillis();
            MailRecord gift = gift(ALICE, BOB, now);
            assertEquals(MailRepository.SendResult.SAVED, repo.send(gift, LIMITS, false));
            assertEquals(MailRepository.SendResult.SAVED, repo.send(gift, LIMITS, false), "same send ID is idempotent");
            assertTrue(repo.activate(gift.id()));
            UUID token = UUID.randomUUID();
            assertTrue(repo.beginClaim(gift.id(), BOB, token, "server-a", now));
            assertTrue(repo.finishClaim(gift.id(), token, null, 0));
            assertTrue(repo.find(gift.id()).isEmpty());
            assertTrue(repo.activate(gift.id()), "sender receipt still has durable activation proof");
            assertTrue(repo.claimAcknowledged(gift.id(), token));
            assertTrue(repo.finishClaim(gift.id(), token, "must-not-resurrect", 1));
            assertTrue(repo.find(gift.id()).isEmpty());
            assertFalse(repo.claimAcknowledged(gift.id(), UUID.randomUUID()));
        }
    }

    @Test
    void interruptedIndexMigrationsRecoverButDifferentIndexDefinitionsAreRejected() throws Exception {
        for (MailDatabases.Db db : MailDatabases.all(dir)) {
            MailRepository repo = repository(db);
            try (var c = db.source().connection(); var statement = c.createStatement()) {
                statement.execute("DELETE FROM allium_schema WHERE component = 'mail'");
            }
            assertEquals(2, repo.migrate(), "DDL completed but version recording was interrupted");
            try (var c = db.source().connection(); var statement = c.createStatement()) {
                statement.execute("DELETE FROM allium_schema WHERE component = 'mail'");
                statement.execute(db.dialect() == codes.castled.allium.storage.SqlDialect.MYSQL
                        ? "DROP INDEX allium_mail_by_sender ON allium_mail" : "DROP INDEX allium_mail_by_sender");
                statement.execute("CREATE INDEX allium_mail_by_sender ON allium_mail (recipient)");
            }
            assertThrows(java.sql.SQLException.class, repo::migrate, "a same-named index with different columns is not accepted");
        }
    }

    @Test
    void concurrentClaimsOfOneGiftHaveExactlyOneWinner() throws Exception {
        for (MailDatabases.Db db : MailDatabases.all(dir)) {
            MailRepository repo = repository(db);
            long now = System.currentTimeMillis();
            MailRecord gift = gift(ALICE, BOB, now);
            repo.send(gift, LIMITS, false);
            repo.activate(gift.id());
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String owner = "server-" + i;
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        if (repo.beginClaim(gift.id(), BOB, UUID.randomUUID(), owner, now)) {
                            winners.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                threads.add(t);
                t.start();
            }
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }
            assertEquals(1, winners.get(), db.name());
        }
    }

    @Test
    void readDeleteClearAndExpiryNeverTouchAGift() throws Exception {
        for (MailDatabases.Db db : MailDatabases.all(dir)) {
            MailRepository repo = repository(db);
            long now = System.currentTimeMillis();
            MailRecord note = text(ALICE, BOB, now);
            MailRecord gift = gift(CAROL, BOB, now);
            repo.send(note, LIMITS, false);
            repo.send(gift, LIMITS, false);
            repo.activate(gift.id());
            assertFalse(repo.deleteText(BOB, gift.id()), db.name() + ": a gift is never deleted");
            assertEquals(0, repo.clearRead(BOB), db.name() + ": unread mail is kept");
            assertEquals(2, repo.markRead(BOB, List.of(note.id(), gift.id())));
            assertEquals(0, repo.unread(BOB, now));
            assertEquals(1, repo.clearRead(BOB), db.name() + ": only the read text mail");
            assertEquals(List.of(gift.id()), repo.mailbox(BOB, now).stream().map(MailRecord::id).toList());

            MailRecord old = text(ALICE, CAROL, now);
            repo.send(old, LIMITS, true);
            long later = old.expiresAt() + 1;
            assertTrue(repo.mailbox(CAROL, later).isEmpty(), db.name() + ": expired text is hidden at once");
            assertEquals(1, repo.purge(later, later - 60_000L), db.name() + ": and purged");
            assertEquals(1, repo.mailbox(BOB, later + 365L * 86_400_000L).size(), db.name() + ": a gift never expires");
        }
    }
}
