package io.github.ppissias.jplatesolve;

import org.junit.Test;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class PlateSolveOperationTest {
    private static final PlateSolveResult SOLVED = new PlateSolveResult(true, null, null, Map.of());

    @Test
    public void queuedCancellationNeverRunsWork() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();
        PlateSolveOperation solve = PlateSolveOperation.start(Duration.ofSeconds(5), null, queued::set, context -> {
            ran.set(true);
            return SOLVED;
        });
        assertTrue(solve.cancel());
        assertFalse(solve.cancel());
        assertEquals(PlateSolveOperation.Status.CANCELLED, solve.completion().toCompletableFuture().get().status());
        queued.get().run();
        assertFalse(ran.get());
    }

    @Test
    public void queueTimeCountsAgainstBudget() throws Exception {
        PlateSolveOperation solve = PlateSolveOperation.start(Duration.ofMillis(50), null, action -> { }, context -> SOLVED);
        assertEquals(PlateSolveOperation.Status.TIMED_OUT, solve.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).status());
    }

    @Test
    public void cancellationCompletionWaitsForCleanup() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            PlateSolveOperation solve = PlateSolveOperation.start(Duration.ofSeconds(5), null, executor, context -> {
                context.onCleanup(outcome -> {
                    cleaning.countDown();
                    release.await();
                });
                started.countDown();
                Thread.sleep(10000);
                return SOLVED;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(solve.cancel());
            assertTrue(cleaning.await(2, TimeUnit.SECONDS));
            assertFalse(solve.isDone());
            release.countDown();
            assertEquals(PlateSolveOperation.Status.CANCELLED, solve.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void committedSuccessWinsLateCancellation() throws Exception {
        PlateSolveOperation solve = PlateSolveOperation.start(Duration.ofSeconds(5), null, Runnable::run, context -> SOLVED);
        assertFalse(solve.cancel());
        assertSame(SOLVED, solve.completion().toCompletableFuture().get().result());
        assertSame(SOLVED, solve.asFuture(false).get());
    }

    @Test
    public void listenerFailureStillRunsCleanup() throws Exception {
        AtomicBoolean cleaned = new AtomicBoolean();
        PlateSolveOperation solve = PlateSolveOperation.start(Duration.ofSeconds(5), progress -> {
            throw new IllegalStateException("listener failed");
        }, Runnable::run, context -> {
            context.onCleanup(outcome -> cleaned.set(true));
            context.report(PlateSolveOperation.Phase.SOLVING, "Solving");
            return SOLVED;
        });
        assertTrue(cleaned.get());
        assertEquals(PlateSolveOperation.Status.FAILED, solve.completion().toCompletableFuture().get().status());
    }

    @Test
    public void cleanupFailureIsNotMisreportedAsCancellation() throws Exception {
        PlateSolveOperation solve = PlateSolveOperation.start(Duration.ofSeconds(5), null, Runnable::run, context -> {
            context.onCleanup(outcome -> { throw new java.io.IOException("cannot stop backend"); });
            return SOLVED;
        });
        assertEquals(PlateSolveOperation.Status.FAILED, solve.completion().toCompletableFuture().get().status());
        assertTrue(solve.completion().toCompletableFuture().get().cause() instanceof PlateSolveOperation.CleanupException);
    }

    @Test
    public void aCommittedAnswerWinsCancellationDuringCleanup() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            PlateSolveOperation solve = PlateSolveOperation.start(Duration.ofSeconds(5), null, executor, context -> {
                context.onCleanup(outcome -> { cleaning.countDown(); release.await(); });
                return SOLVED;
            });
            assertTrue(cleaning.await(2, TimeUnit.SECONDS));
            assertFalse(solve.isDone());
            assertFalse(solve.cancel());
            release.countDown();
            assertEquals(PlateSolveOperation.Status.SUCCEEDED, solve.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellingALegacyFutureStillStopsTheOperation() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        PlateSolveOperation solve = PlateSolveOperation.start(Duration.ofSeconds(5), null, queued::set, context -> SOLVED);
        var future = solve.asFuture(false);
        assertTrue(future.cancel(true));
        assertTrue(future.isCancelled());
        assertEquals(PlateSolveOperation.Status.CANCELLED, solve.completion().toCompletableFuture().get().status());
        queued.get().run();
    }
}
