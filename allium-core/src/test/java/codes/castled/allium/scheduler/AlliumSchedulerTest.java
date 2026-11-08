package codes.castled.allium.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AlliumSchedulerTest {

    /** A server thread driven by hand: {@link #tick()} runs whatever is due. */
    static final class ManualServer implements ServerThread {
        final class Entry implements TaskHandle {
            final Runnable task;
            long due;
            final long period;
            boolean cancelled;

            Entry(Runnable task, long due, long period) {
                this.task = task;
                this.due = due;
                this.period = period;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        }

        final List<Entry> entries = new ArrayList<>();
        final List<long[]> requested = new ArrayList<>();
        long now;
        boolean onServer;

        @Override
        public boolean isServerThread() {
            return onServer;
        }

        @Override
        public TaskHandle schedule(Runnable task, long delayTicks, long periodTicks) {
            requested.add(new long[]{delayTicks, periodTicks});
            Entry entry = new Entry(task, now + Math.max(1L, delayTicks), periodTicks);
            entries.add(entry);
            return entry;
        }

        void tick() {
            now++;
            onServer = true;
            try {
                for (Entry entry : new ArrayList<>(entries)) {
                    if (entry.cancelled || entry.due != now) {
                        continue;
                    }
                    entry.task.run();
                    if (entry.period > 0L && !entry.cancelled) {
                        entry.due = now + entry.period;
                    } else {
                        entries.remove(entry);
                    }
                }
            } finally {
                onServer = false;
            }
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                tick();
            }
        }
    }

    private final ManualServer server = new ManualServer();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final AlliumScheduler scheduler = new AlliumScheduler(server, errors::add);

    @Test
    void serverDelaysRoundUpToWholeTicks() {
        assertEquals(0L, AlliumScheduler.ticks(0L));
        assertEquals(1L, AlliumScheduler.ticks(1L));
        assertEquals(1L, AlliumScheduler.ticks(50L));
        assertEquals(3L, AlliumScheduler.ticks(120L));
        scheduler.runOnServer(() -> { });
        scheduler.runOnServerLater(() -> { }, 120L);
        scheduler.repeatOnServer(() -> true, 30_000L, 1_000L);
        assertArrayEquals(new long[]{0L, 0L}, server.requested.get(0));
        assertArrayEquals(new long[]{3L, 0L}, server.requested.get(1));
        assertArrayEquals(new long[]{600L, 20L}, server.requested.get(2));
    }

    @Test
    void serverTasksRunOnTheServerThreadAndCanBeCancelled() {
        AtomicReference<Boolean> onServer = new AtomicReference<>();
        scheduler.runOnServer(() -> onServer.set(scheduler.isServerThread()));
        AtomicInteger cancelledRuns = new AtomicInteger();
        TaskHandle cancelled = scheduler.runOnServerLater(cancelledRuns::incrementAndGet, 100L);
        assertEquals(2, scheduler.scheduledServerTasks());
        cancelled.cancel();
        server.ticks(5);
        assertEquals(Boolean.TRUE, onServer.get());
        assertEquals(0, cancelledRuns.get());
        assertEquals(0, scheduler.scheduledServerTasks(), "one-shot tasks are forgotten once run or cancelled");
        assertFalse(scheduler.isServerThread());
    }

    @Test
    void aRepeatingTaskStopsWhenItsStepSaysSoAndSurvivesAThrow() {
        AtomicInteger runs = new AtomicInteger();
        scheduler.repeatOnServer(() -> {
            int n = runs.incrementAndGet();
            if (n == 2) {
                throw new IllegalStateException("boom");
            }
            return n < 4;
        }, 50L, 50L);
        server.ticks(10);
        assertEquals(4, runs.get(), "ran until the step returned false, despite the throw on run 2");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("boom"), errors.get(0));
        assertEquals(0, scheduler.scheduledServerTasks());
    }

    @Test
    void asyncTasksRunOffTheServerThreadAfterTheirDelayAndAThrowDoesNotStopTheTimer() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        long started = System.nanoTime();
        scheduler.runAsyncLater(() -> {
            throw new IllegalStateException("first task fails");
        }, 0L);
        AtomicInteger cancelledRuns = new AtomicInteger();
        scheduler.runAsyncLater(cancelledRuns::incrementAndGet, 100L).cancel();
        scheduler.runAsyncLater(() -> {
            thread.set(Thread.currentThread().getName());
            done.countDown();
        }, 150L);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(140L));
        assertEquals("Allium-Timer", thread.get());
        assertEquals(0, cancelledRuns.get());
        assertEquals(1, errors.size());
    }

    @Test
    void boundedPoolsAreNamedBoundedAndRejectWhenFull() throws Exception {
        ThreadPoolExecutor pool = scheduler.boundedPool("Allium-Test", 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> name = new AtomicReference<>();
        pool.execute(() -> {
            name.set(Thread.currentThread().getName());
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pool.execute(() -> { });
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }), "a full queue rejects instead of growing");
        release.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("Allium-Test-0", name.get());
    }

    @Test
    void closeCancelsEverythingAndRefusesNewWork() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        scheduler.repeatOnServer(() -> {
            runs.incrementAndGet();
            return true;
        }, 50L, 50L);
        scheduler.runOnServerLater(runs::incrementAndGet, 200L);
        scheduler.runAsyncLater(runs::incrementAndGet, 200L);
        ThreadPoolExecutor stuck = scheduler.boundedPool("Allium-Stuck", 1, 4);
        CountDownLatch never = new CountDownLatch(1);
        stuck.execute(() -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ThreadPoolExecutor idle = scheduler.boundedPool("Allium-Idle", 1, 4);
        assertEquals(List.of("Allium-Stuck"), scheduler.close(200L), "the pool still working is named and interrupted");
        assertTrue(idle.isTerminated());
        assertTrue(stuck.awaitTermination(5, TimeUnit.SECONDS), "interrupted work ends");
        server.ticks(10);
        Thread.sleep(300L);
        assertEquals(0, runs.get(), "nothing scheduled before close runs after it");
        assertSame(TaskHandle.NONE, scheduler.runOnServer(runs::incrementAndGet));
        assertSame(TaskHandle.NONE, scheduler.runAsyncLater(runs::incrementAndGet, 0L));
        assertThrows(IllegalStateException.class, () -> scheduler.boundedPool("Allium-Late", 1, 1));
        assertTrue(scheduler.isClosed());
    }
}
