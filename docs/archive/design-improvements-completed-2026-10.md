# Design improvements completed in October 2026

Completed work is recorded here; [DESIGN-IMPROVEMENTS.md](../../DESIGN-IMPROVEMENTS.md) contains only pending changes. Current API usage is in [README.md](../../README.md).

## JP-01, JP-02 and JP-05: process ownership, cancellation and client lifecycle

Implemented on 2026-10-21 for **JPlateSolve 1.0.1**, following the request to make solving cancellable in the local library and adopt it in SpacePixels. The project already declared 1.0.1; its README and SpacePixels dependency now agree. Nothing was published.

### Decision and behaviour

- Added `PlateSolveOperation`: cancellation request separate from a completion stage that resolves only after cleanup. Terminal states distinguish successful solution, solver rejection, failure, timeout and cancellation. A first accepted stop wins over a later answer; an answer committed before Cancel is retained. Cleanup failure is a failure, not an assertion that stopping succeeded.
- Cancellation interrupts the worker independently of progress reporting. Progress carries phase and elapsed time. Callbacks must return promptly; exceptions still trigger cleanup. Queued cancellation clears the work without launching a process or making an HTTP call.
- ASTAP owns its child process through exit. Output is merged and continuously drained with bounded retained diagnostics. Cancellation/timeout requests graceful termination, escalates to a forced kill, and confirms exit before completion. Observed descendants are stopped too. Cleanup that cannot confirm termination fails explicitly.
- ASTAP moves pre-existing sidecars into an owned backup directory before launching. Unsuccessful runs delete their generated sidecars and restore those backups. Successful runs keep fresh outputs. The output stem is exclusive within the JVM, including colliding .fit/.fits names; external applications must not use the same stem concurrently. Extensionless names and dotted parent directories are handled without truncating the source path. A fresh WCS file is required for success; stale .ini/.wcs files cannot satisfy the solve. Partial preparation and failed launch restore moved files.
- The monotonic solve deadline starts before queueing and covers preparation, login, launch, upload and polling. Defaults remain ASTAP 2 minutes and cloud 15 minutes; cleanup follows. HTTP waits are capped by the remaining budget. Cloud operations stop local requests/polling only: no promise is made to cancel an accepted server job.
- Login and FITS preparation now happen inside the cloud operation. Custom parameters are snapshotted, without changing existing numeric-hint or blind-upload visibility defaults. Request-shape/coordinate corrections and API-key configuration remain JP-03/JP-04.
- Existing Future APIs remain adapters. Cloud preparation errors are now asynchronous. Legacy Future cancellation may complete get() before cleanup; new integrations retain the operation and join its completion stage. cancel(false) does not interrupt the worker.
- The cloud client tracks its own queued/running operations, including those using a borrowed executor. Close cancels them, shuts down HTTP resources and an owned executor, and waits with a shared bounded deadline. A borrowed executor is never shut down. New operations after close are rejected; repeated close is harmless.
- SpacePixels enables Cancel for Plate Solve, displays phase/elapsed indeterminate progress, joins solver cleanup even when its waiting thread is interrupted, and keeps coordinator admission locked until then. The GUI no longer deletes files restored by a failed/cancelled solve. Successful WCS import and post-success cleanup remain application responsibilities. ImageProcessing closes the cloud client with its session.

### Validation

Default JDK 21 validation passes **31 library tests** and **322 SpacePixels tests (4 skipped, 318 passed)**. Coverage includes queued cancellation and timeout, terminal races, interrupted waiters, cleanup failure, listener failure, process termination, forced-stop escalation after an ignored graceful stop, large diagnostic output, nonzero-exit rejection, backup restoration, missing fresh WCS, failed launch, extensionless/source-safe paths, same-stem exclusion, HTTP cancellation during login/streaming upload/polling, total-budget login timeout, success calibration/private parameters, client close during upload, repeated close and borrowed-executor ownership. SpacePixels tests confirm the job slot stays locked through cleanup and that a subsequent job cannot overlap it.

A representative stand-in run measured Cancel to confirmed child exit plus operation completion at **24.956 ms**. Loopback cancellation to local completion measured **0.448 ms** during login, **11.681 ms** during a 32 MiB streaming upload, and **1.376 ms** during polling. These are synthetic contract measurements, not ASTAP solving/61-megapixel performance or an external-service cancellation guarantee. No observer data was uploaded, no production key was used and no real ASTAP or live Astrometry.net solve was run.

Built the 1.0.1 jar, sources, Javadoc and POM. javap confirms Java class-file major **65** (Java 21). SpacePixels passed with the composite dependency and with composite JPlateSolve disabled for that invocation, resolving the same jar and generated POM from an isolated scratch Maven repository. Local-development flags in gradle.properties remain true. No Maven Local/Central publication occurred.

The running SpacePixels application held the normal dependency jars open on Windows. Validation redirected Jar task outputs to ignored cancellation-validation-libs directories using temporary init scripts, without stopping the application or changing committed build defaults.

### Remaining scope

JP-03 (numeric omission/coordinate parsing), JP-04 (observer API key), JP-06 (measured large-frame search/upload tuning), JP-07 (immutable/normalized metadata and richer protocol diagnostics) and the remaining JP-08 doclint/live-test-key checks stay open. Cancellation's operation categories are implemented; broader public result-contract changes are not.

## Original review context

The following sections preserve the original evidence and acceptance proposals; statements about missing cancellation or ownership describe the pre-implementation code, not current behaviour.

## JP-01. Own the ASTAP process until it has exited

**Evidence.** [ASTAPInterface.solveImage](src/main/java/io/github/ppissias/jplatesolve/astap/ASTAPInterface.java) starts `astap` with `Runtime.exec` and never reads standard output or standard error. It then sleeps two seconds. If the process has already exited with a code above zero, the future fails with that code and the `.ini` is never opened. If the process is still alive, the exit code is never observed again and the process is never destroyed.

[ASTAPSolveResultsReader](src/main/java/io/github/ppissias/jplatesolve/astap/ASTAPSolveResultsReader.java) then waits until a sibling `.ini` exists, up to two minutes, polling every 250 ms. The deadline starts when that wait starts, after the two-second sleep. The reader returns as soon as the file exists. It does not record the process start time, so an `.ini` left by an earlier run satisfies the wait immediately. A file that exists and is still being written is parsed at once. `Future.cancel(true)` interrupts the worker thread. The worker is a daemon. Neither cancellation, interruption during the opening sleep, nor the timeout destroys the process.

The expected filenames come from the last `.` in the full path. A path with no dot throws `StringIndexOutOfBoundsException`. A directory whose name contains a dot and a file with no extension, such as `C:\data.set\frame`, is paired with `C:\data.ini`.

**Risk and proposal.** An unread pipe can stall a child once the buffer fills. The two-minute wait then expires while ASTAP is blocked or still searching, and the process keeps running. It can write the `.ini` after SpacePixels has already treated the solve as failed and cleaned up. The next solve of the same frame sees that file and can return the previous solution while a new ASTAP is writing the same path. A fast non-zero exit hides the `ERROR` text ASTAP writes into the `.ini`.

Treat the process as the completion signal. Drain both streams on the worker, or the caller-supplied log sink. Wait until the process has exited, then parse an `.ini` that contains `PLTSOLVD`. Ignore an `.ini` whose last-modified time is earlier than the launch. On timeout, cancellation and failure, destroy the process and wait for it to exit before the future completes. Remove a partial file from that run. Derive sibling names from the file name, and keep the full path unchanged when the name has no dot.

Keep the argument array and the executable's directory as the working directory. Keep `-wcs`.

**Review feedback — output ownership and bounded cleanup.** Modification time alone cannot identify this run's outputs: timestamp precision and overlapping solves can admit an old file. Prefer isolated per-operation output paths where ASTAP supports them; otherwise define exclusive ownership of the output stem and a pre-launch policy for existing sidecars. `frame.fit` and `frame.fits` in the same directory also collide. Verify the `.wcs` belongs to this run before exposing its link, since SpacePixels imports that file. Cleanup must delete only files owned by this operation. For extensionless input, preserve the input path and append the output suffix; never use the input itself as a sidecar.

Drain stdout and stderr concurrently with the process wait, or merge and drain them on a separate task. Reading one stream to EOF before the other, or before checking the deadline, can still hang. Bound retained diagnostics and isolate a slow or throwing log sink. Start a monotonic deadline before launch; on expiry request termination, escalate to forced termination after a bounded grace period, and report cleanup failure if exit cannot be confirmed. Add overlapping-stem, stale-WCS and termination-escalation cases to acceptance.

**Acceptance.** With a stand-in executable: a verbose child that fills the pipe still exits; a child that exits in under two seconds yields the `.ini` error text; a pre-existing `.ini` is not returned as this run's solution; a timeout and a cancel both leave no `astap` process; a path with no extension does not throw. Repeat a solve of one file after a forced timeout and confirm the second result comes from the second process.

## JP-02. Report progress and honour cancellation

**Evidence.** A plate solve in SpacePixels is [JobKind.PLATE_SOLVE](../SpacePixels/src/main/java/eu/startales/spacepixels/jobs/JobKind.java), and that kind is not cancellable. The comment records why: plate solving waits on an external solver. The progress dialog therefore offers no Cancel. The status text is the indeterminate line "Plate solving selected image...". [PlateSolveTask](../SpacePixels/src/main/java/eu/startales/spacepixels/tasks/PlateSolveTask.java) blocks on `Future.get()` for the whole operation.

Inside the library the waits are fixed and silent. ASTAP polls a file for up to two minutes (JP-01). Astrometry.net polls every five seconds for up to fifteen minutes ([AstrometryDotNet](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/AstrometryDotNet.java), `DEFAULT_SOLVE_TIMEOUT` and `DEFAULT_POLL_INTERVAL`). There is no listener. SpacePixels calls the two-argument `solveImage` and `new AstrometryDotNet()`, so the application never passes the durations the library already accepts.

This is the same gap SpacePixels D04 and JTransient JA-03 describe for detection: a cancel is real only where the engine checks it, and the time the user waits is the time until the next check. For plate solving the application cannot enter that arrangement, because this library has no check to enter.

**Risk and proposal.** A blind ASTAP search that overruns two minutes becomes a timeout with the process still running (JP-01). An Astrometry.net job that is slow or pointed at the wrong sky (JP-03) occupies the window for up to fifteen minutes, with no Cancel and no phase. Closing the future's thread does not define when the solver has stopped.

Add a small operation handle shared by both backends:

- a cancel request the worker checks between the launch, each poll and each HTTP call;
- a listener invoked on the worker for phase and, where known, completed and total work: launching, uploading, waiting for a job, solving, reading the result;
- a terminal state that separates cancelled, timed out, rejected by the solver, and failed.

Cancellation destroys the ASTAP process as in JP-01, interrupts the HTTP call, and stops polling. The future completes only after that work has finished. An Astrometry.net job already accepted by the server may keep running there; the handle documents that the local wait has stopped. Keep today's `Future<PlateSolveResult>` methods as adapters that supply no listener and the current default durations.

**Review feedback — cancellation needs a separate completion contract.** Today's `FutureTask.cancel(true)` can release `get()` with `CancellationException` before worker cleanup finishes. A `finally` block alone cannot make that future certify process exit. Give the new handle a cancel-request method and a completion stage that resolves after cleanup, including a cancelled outcome; document the weaker semantics of legacy futures. Define the winner of a cancel/success race and test cancellation before launch, while queued, and during result parsing as well as during active work. See the [Java Future contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html).

Also move login and FITS-header preparation inside the new operation. Currently `blindSolve` and `customSolve` can perform synchronous login before returning a future, and `customSolve` reads the header synchronously. Returning a future does not currently guarantee a non-blocking entry point. Specify listener exception handling and require callbacks to return promptly, or dispatch them through a bounded mechanism so they cannot hold up cancellation.

SpacePixels can then mark plate solve cancellable, forward the listener to `JobContext.progress`, and pass a duration. The application change belongs in SpacePixels after the library method exists. Suggested shape once this is agreed:

- Cancel on the plate-solve dialog, using the same coordinator path as detection.
- Progress text for the phase, plus elapsed time. Counts only when the solver actually provides them.
- A documented solve budget for ASTAP and for Astrometry.net, passed in, with the library defaults remaining for other callers.
- Measure the time from Cancel to process exit and to the job's cancelled outcome, on a large frame, and record it beside the D04 engine timings.

**Acceptance.** Cancel during the ASTAP wait, during upload and during Astrometry.net polling. Confirm one terminal state, no successful result after cancel, and no leftover ASTAP process. A slow listener must not be required for correctness. A solve that receives no cancel still returns the same result as today's adapter on a fixture.

## JP-05. Close the client with the session that created it

**Evidence.** The no-argument client creates a fixed pool of three threads and an `HttpClient`. `Executors.newFixedThreadPool` uses non-daemon threads. `close` calls `shutdownNow` on the pool when this instance created it, and does not wait for the threads to finish. It does not close the `HttpClient`. On the Java 21 baseline the README asks for, `HttpClient` implements `AutoCloseable`.

SpacePixels constructs one client inside every `ImageProcessing`, via `PlateSolveService`. [ImageProcessing.close](../SpacePixels/src/main/java/eu/startales/spacepixels/util/ImageProcessing.java) shuts down a different executor. Nothing calls `AstrometryDotNet.close`. Each import builds a new processor and closes the previous one.

**Risk and proposal.** The pool threads are live roots. After the processor is closed and dropped, those three threads and the client they keep reachable stay for the life of the process. A second import adds another three. The HTTP client's own threads follow the same pattern while the client stays open.

`close` should shut the pool down, await termination for a bounded interval, and close the `HttpClient` when this instance created it. A caller-supplied executor stays the caller's, as the config type already says. An in-flight solve is cancelled as in JP-02 before the pool stops, so `shutdownNow` is not the only signal.

SpacePixels calls that `close` from `ImageProcessing.close`. Until JP-02 exists, closing may interrupt a solve that is still running; the application already refuses a second long job, and close runs when the session is replaced or the window exits.

**Review feedback — qualify the leak and close semantics.** The fixed pool creates threads lazily: constructing a processor does not immediately add three threads. Submitted solves can leave up to three non-daemon workers per unclosed client; idle pool workers alone do not prove that the client itself remains reachable. Keep the executor leak finding, but measure HTTP-client retention separately.

Java 21 `HttpClient.close()` waits for outstanding operations, so calling it alone does not provide bounded shutdown. Define cancellation, `shutdown`/`shutdownNow` and bounded `awaitTermination` sequencing for both resources, with an explicit outcome if termination fails; see the [HttpClient lifecycle contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html). Track this client's operations even on a caller-owned executor: cancel those operations without shutting that executor down. Explicitly cancel queued futures removed by executor shutdown so they cannot remain pending forever. Test close during an upload, close before first use, and rejection of new solves after close.

**Acceptance.** Open a client, run a solve against a local stub, close, and confirm the pool's threads are gone and a second close is harmless. Construct two clients and close one; the other still completes. A SpacePixels-level test, or a focused lifecycle test, that opens two processors and closes the first shows a single live plate-solve pool.
