package io.github.ppissias.jplatesolve;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** A solve whose completion is delivered after local work and cleanup have ended. */
public final class PlateSolveOperation {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, REJECTED, FAILED, TIMED_OUT, CANCELLED }
    public enum Phase { PREPARING, LOGGING_IN, LAUNCHING, UPLOADING, WAITING_FOR_JOB, SOLVING, READING_RESULT }
    public record Progress(Phase phase, Duration elapsed, String message) { }
    public record Outcome(Status status, PlateSolveResult result, Throwable cause) { }

    @FunctionalInterface
    public interface Work {
        PlateSolveResult run(Context context) throws Exception;
    }

    @FunctionalInterface
    public interface Cleanup {
        void run(Outcome outcome) throws Exception;
    }

    public static final class CleanupException extends IOException {
        public CleanupException(String message) { super(message); }
        public CleanupException(String message, Throwable cause) { super(message, cause); }
    }

    private static final ScheduledThreadPoolExecutor DEADLINES = createDeadlineExecutor();
    private final Object lock = new Object();
    private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
    private final Consumer<Progress> listener;
    private final long startedNanos = System.nanoTime();
    private final long budgetNanos;
    private final List<Cleanup> cleanup = new ArrayList<>();
    private Work work;
    private Thread worker;
    private ScheduledFuture<?> deadline;
    private Status stopped;
    private boolean committed;

    private PlateSolveOperation(Duration budget, Consumer<Progress> listener, Work work) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative() || budget.isZero()) throw new IllegalArgumentException("Solve budget must be positive.");
        long nanos;
        try { nanos = budget.toNanos(); } catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE; }
        this.budgetNanos = nanos;
        this.listener = listener;
        this.work = Objects.requireNonNull(work, "work");
    }

    /** Starts backend work; the budget includes time spent waiting in the executor queue. */
    public static PlateSolveOperation start(Duration budget, Consumer<Progress> listener, Executor executor, Work work) {
        PlateSolveOperation operation = new PlateSolveOperation(budget, listener, work);
        operation.deadline = DEADLINES.schedule(() -> operation.stop(Status.TIMED_OUT), operation.budgetNanos, TimeUnit.NANOSECONDS);
        operation.completion.whenComplete((outcome, failure) -> operation.deadline.cancel(false));
        try {
            executor.execute(operation::run);
        } catch (RuntimeException rejected) {
            operation.finishWithoutWorker(new Outcome(Status.FAILED, null, rejected));
        }
        return operation;
    }

    /** Requests cancellation. A false return means an earlier stop or terminal outcome already won the race. */
    public boolean cancel() {
        return stop(Status.CANCELLED);
    }

    /** Resolves normally with a typed outcome, including cancellation, only after backend cleanup. */
    public CompletionStage<Outcome> completion() {
        return completion.minimalCompletionStage();
    }

    public boolean isDone() {
        return completion.isDone();
    }

    public Status status() {
        synchronized (lock) {
            Outcome outcome = completion.getNow(null);
            return outcome != null ? outcome.status() : worker == null ? Status.QUEUED : Status.RUNNING;
        }
    }

    /** Legacy Future cancellation requests the stop but may release get() before cleanup; use completion() to join. */
    public Future<PlateSolveResult> asFuture(boolean failuresAsResults) {
        CompletableFuture<PlateSolveResult> future = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (!stop(Status.CANCELLED, mayInterruptIfRunning)) return false;
                return super.cancel(mayInterruptIfRunning);
            }
        };
        completion.thenAccept(outcome -> {
            switch (outcome.status()) {
                case SUCCEEDED, REJECTED -> future.complete(outcome.result());
                case CANCELLED -> future.completeExceptionally(new CancellationException("Plate solve cancelled."));
                case FAILED, TIMED_OUT -> {
                    if (failuresAsResults && !(outcome.cause() instanceof Error)) {
                        future.complete(new PlateSolveResult(false, outcome.cause().getMessage(), null, null));
                    } else {
                        future.completeExceptionally(outcome.cause());
                    }
                }
                default -> throw new IllegalStateException("Non-terminal solve outcome.");
            }
        });
        return future;
    }

    private boolean stop(Status reason) {
        return stop(reason, true);
    }

    private boolean stop(Status reason, boolean interruptWorker) {
        Outcome outcome = null;
        synchronized (lock) {
            if (committed || stopped != null) return false;
            stopped = reason;
            if (worker != null && interruptWorker) {
                worker.interrupt();
            } else if (worker == null) {
                committed = true;
                work = null;
                outcome = stopOutcome(reason, null);
            }
        }
        if (outcome != null) completion.complete(outcome);
        return true;
    }

    private void finishWithoutWorker(Outcome outcome) {
        synchronized (lock) {
            if (committed) return;
            committed = true;
            work = null;
        }
        completion.complete(outcome);
    }

    private void run() {
        Work action;
        synchronized (lock) {
            if (committed) return;
            worker = Thread.currentThread();
            action = work;
            work = null;
        }
        PlateSolveResult result = null;
        Throwable failure = null;
        Context context = new Context();
        try {
            context.checkCancelled();
            result = action.run(context);
            context.checkCancelled();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stop(Status.CANCELLED);
            failure = interrupted;
        } catch (CancellationException cancelled) {
            stop(Status.CANCELLED);
            failure = cancelled;
        } catch (Throwable problem) {
            failure = problem;
        }
        Outcome outcome;
        synchronized (lock) {
            committed = true;
            if (failure instanceof Error || failure instanceof CleanupException) {
                outcome = new Outcome(Status.FAILED, null, failure);
            } else if (stopped != null) {
                outcome = stopOutcome(stopped, failure);
            } else if (failure != null) {
                outcome = new Outcome(Status.FAILED, null, failure);
            } else if (result == null) {
                outcome = new Outcome(Status.FAILED, null, new IllegalStateException("Solver returned no result."));
            } else {
                outcome = new Outcome(result.isSuccess() ? Status.SUCCEEDED : Status.REJECTED, result, null);
            }
        }
        boolean interrupted = Thread.interrupted();
        try {
            for (int index = cleanup.size() - 1; index >= 0; index--) {
                try {
                    cleanup.get(index).run(outcome);
                } catch (Throwable problem) {
                    if (outcome.cause() != null && outcome.cause() != problem) problem.addSuppressed(outcome.cause());
                    outcome = new Outcome(Status.FAILED, null, problem instanceof Error ? problem
                            : new CleanupException("Plate-solve cleanup failed: " + problem.getMessage(), problem));
                }
            }
        } finally {
            interrupted |= Thread.interrupted();
            if (interrupted) Thread.currentThread().interrupt();
            synchronized (lock) { worker = null; }
            completion.complete(outcome);
        }
    }

    private static Outcome stopOutcome(Status reason, Throwable cause) {
        Exception failure = reason == Status.CANCELLED ? new CancellationException("Plate solve cancelled.")
                : new TimeoutException("Plate solve exceeded its total time budget.");
        if (cause != null) failure.initCause(cause);
        return new Outcome(reason, null, failure);
    }

    private static ScheduledThreadPoolExecutor createDeadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "jplatesolve-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public final class Context {
        private Context() { }

        public void checkCancelled() {
            if (Thread.currentThread().isInterrupted()) stop(Status.CANCELLED);
            if (System.nanoTime() - startedNanos >= budgetNanos) stop(Status.TIMED_OUT);
            synchronized (lock) {
                if (stopped != null) throw new CancellationException("Plate solve stopped: " + stopped);
            }
        }

        public Duration remaining() {
            checkCancelled();
            return Duration.ofNanos(Math.max(1, budgetNanos - (System.nanoTime() - startedNanos)));
        }

        public void report(Phase phase, String message) {
            checkCancelled();
            if (listener != null) listener.accept(new Progress(phase, Duration.ofNanos(System.nanoTime() - startedNanos), message));
            checkCancelled();
        }

        public void onCleanup(Cleanup action) {
            cleanup.add(Objects.requireNonNull(action));
        }

        public void pause(Duration duration) throws InterruptedException {
            checkCancelled();
            TimeUnit.NANOSECONDS.sleep(Math.min(duration.toNanos(), remaining().toNanos()));
            checkCancelled();
        }
    }
}
