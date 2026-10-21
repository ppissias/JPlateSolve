# Design improvements and review decisions

Reviewed against the source tree on 2026-10-21, at commit `7b5cb30`. This is the decision list for JPlateSolve. It holds **pending work and open decisions**. The library is the plate-solving engine behind [SpacePixels](../SpacePixels): local solves through ASTAP, and online solves through the Astrometry.net HTTP API.

The same kind of list for the detection engine is [JTransient's design list](../JTransient/DESIGN-IMPROVEMENTS.md). The application list is [SpacePixels' design list](../SpacePixels/DESIGN-IMPROVEMENTS.md). Those documents already record that a plate solve waits on this library with no cancel. This review is the library side of that wait.

## Status and scope

**Proposed** is an idea without an implementation decision. Priority is separate from authorization. High priority means the contract should be settled before the affected use grows. It does not by itself authorize a change, and a source defect here is not a measurement on a particular night's data.

- **When an item is done, declined or retired,** describe the resulting behaviour in [README.md](README.md) and move the decision and the evidence to an archive. Leave this file as the open list.
- **One change at a time.** A process-lifetime fix (JP-01) and a request-shape fix (JP-03) can be discussed separately. SpacePixels adopts a new method only after the library version that contains it is named.

Two checks were executed while reading the code. With Gson 2.13.2, a request object left at the primitive defaults serializes as `center_ra`, `center_dec`, `radius` and `positional_error` all present and numeric, including zeros. `Float.parseFloat("-0")` and `Float.parseFloat("-00")` are negative zero, and negative zero is not less than zero. ASTAP itself was not run for this review.

## What to preserve

- **A small engine with two backends and one result.** Callers solve through [ASTAPInterface](src/main/java/io/github/ppissias/jplatesolve/astap/ASTAPInterface.java) or [AstrometryDotNet](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/AstrometryDotNet.java) and read a [PlateSolveResult](src/main/java/io/github/ppissias/jplatesolve/PlateSolveResult.java). The library stays free of Swing, FITS writing and session folders. SpacePixels owns the header update and the deletion of solver files.
- **The caller chooses the waiting thread.** Both entry points return a `Future`. SpacePixels already waits on its plate-solve worker, in [PlateSolveTask](../SpacePixels/src/main/java/eu/startales/spacepixels/tasks/PlateSolveTask.java). Keep that split.
- **The ASTAP launch shape that is already right.** The executable is started with an argument array from the executable's directory, so a path with spaces needs no shell and the star database beside the program can be found. `-wcs` asks for the FITS-shaped header SpacePixels imports.
- **The streaming upload.** [SubmitFileBodyPublisher.getBodyPublisher](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/util/SubmitFileBodyPublisher.java) sends the file from disk. The in-memory multipart helper is a separate diagnostic path. Keep the solve on the streaming path.
- **Timeouts that a caller can already set.** The ASTAP reader takes a `Duration`. [AstrometryDotNetConfig](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/AstrometryDotNetConfig.java) takes a per-request timeout, a solve timeout and a poll interval. The gap is that the defaults, and the SpacePixels call, do not use that room. See JP-02 and JP-06.
- **Private uploads from SpacePixels.** [PlateSolveService](../SpacePixels/src/main/java/eu/startales/spacepixels/util/PlateSolveService.java) sends `publicly_visible=n`. Keep that. The library's own blind-solve default is a separate question in JP-03.
- **The existing ini checks.** [ASTAPSolveResultsReaderTest](src/test/java/io/github/ppissias/jplatesolve/astap/ASTAPSolveResultsReaderTest.java) covers a successful ini, a failed ini and a missing file. Extend that style for the new contracts. Live Astrometry.net calls stay on `integrationTest`, off the default `test` task.

## Findings and priorities

| ID | Priority | Status | Design improvement | First useful result |
|---|---|---|---|---|
| JP-01 | High | Proposed | Own the ASTAP process until it has exited | A timeout, a failure or a second solve cannot observe a stale `.ini` or leave `astap` running. |
| JP-02 | High | Proposed | Report progress and honour cancellation for both solvers | SpacePixels can offer Cancel during a plate solve, and the wait ends when the user cancels. |
| JP-03 | High | Proposed | Send only the constraints the caller set, and parse sexagesimal signs | A frame with no pointing header is a blind solve. A declination of −0° 30′ stays south of the equator. |
| JP-04 | High | Proposed | Take the Astrometry.net API key from the caller | Online solving uses the observer's own key. The placeholder constant is retired. |
| JP-05 | Medium | Proposed | Close the HTTP client and its threads with the session | Importing another folder does not leave the previous client's threads running. |
| JP-06 | Medium | Proposed | Let a known field shorten the search, and size timeouts for a large frame | A 61-megapixel frame can carry a radius, a scale and an upload budget. |
| JP-07 | Medium | Proposed | One result contract for success, rejection, timeout and cancellation | The application can classify a failure without reading prose, including failures after the upload has started. |
| JP-08 | Medium | Proposed | Encode the Java target and test the contracts offline | The behaviours above are checked on the declared JDK, without a live call to Astrometry.net. |

## JP-01. Own the ASTAP process until it has exited

**Evidence.** [ASTAPInterface.solveImage](src/main/java/io/github/ppissias/jplatesolve/astap/ASTAPInterface.java) starts `astap` with `Runtime.exec` and never reads standard output or standard error. It then sleeps two seconds. If the process has already exited with a code above zero, the future fails with that code and the `.ini` is never opened. If the process is still alive, the exit code is never observed again and the process is never destroyed.

[ASTAPSolveResultsReader](src/main/java/io/github/ppissias/jplatesolve/astap/ASTAPSolveResultsReader.java) then waits until a sibling `.ini` exists, up to two minutes, polling every 250 ms. The deadline starts when that wait starts, after the two-second sleep. The reader returns as soon as the file exists. It does not record the process start time, so an `.ini` left by an earlier run satisfies the wait immediately. A file that exists and is still being written is parsed at once. `Future.cancel(true)` interrupts the worker thread. The worker is a daemon. Neither cancellation, interruption during the opening sleep, nor the timeout destroys the process.

The expected filenames come from the last `.` in the full path. A path with no dot throws `StringIndexOutOfBoundsException`. A directory whose name contains a dot and a file with no extension, such as `C:\data.set\frame`, is paired with `C:\data.ini`.

**Risk and proposal.** An unread pipe can stall a child once the buffer fills. The two-minute wait then expires while ASTAP is blocked or still searching, and the process keeps running. It can write the `.ini` after SpacePixels has already treated the solve as failed and cleaned up. The next solve of the same frame sees that file and can return the previous solution while a new ASTAP is writing the same path. A fast non-zero exit hides the `ERROR` text ASTAP writes into the `.ini`.

Treat the process as the completion signal. Drain both streams on the worker, or the caller-supplied log sink. Wait until the process has exited, then parse an `.ini` that contains `PLTSOLVD`. Ignore an `.ini` whose last-modified time is earlier than the launch. On timeout, cancellation and failure, destroy the process and wait for it to exit before the future completes. Remove a partial file from that run. Derive sibling names from the file name, and keep the full path unchanged when the name has no dot.

Keep the argument array and the executable's directory as the working directory. Keep `-wcs`.

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

SpacePixels can then mark plate solve cancellable, forward the listener to `JobContext.progress`, and pass a duration. The application change belongs in SpacePixels after the library method exists. Suggested shape once this is agreed:

- Cancel on the plate-solve dialog, using the same coordinator path as detection.
- Progress text for the phase, plus elapsed time. Counts only when the solver actually provides them.
- A documented solve budget for ASTAP and for Astrometry.net, passed in, with the library defaults remaining for other callers.
- Measure the time from Cancel to process exit and to the job's cancelled outcome, on a large frame, and record it beside the D04 engine timings.

**Acceptance.** Cancel during the ASTAP wait, during upload and during Astrometry.net polling. Confirm one terminal state, no successful result after cancel, and no leftover ASTAP process. A slow listener must not be required for correctness. A solve that receives no cancel still returns the same result as today's adapter on a fixture.

## JP-03. Send only the constraints the caller set

**Evidence.** [SubmitFileRequest](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/SubmitFileRequest.java) stores coordinates, radius, scale and `positional_error` as primitive `float`s. The primitive default is zero. Gson writes those zeros. A request that sets only a session and a radius of 10 serializes with `center_ra` 0, `center_dec` 0, `radius` 10 and `positional_error` 0.

The Astrometry.net upload API uses `center_ra`, `center_dec` and `radius` together as a known position and a search radius. `positional_error` defaults to 1 when it is absent. The official client omits a field it was not given.

[AstrometryDotNet.customSolve](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/AstrometryDotNet.java) treats a center of zero as "not set" and may overwrite it from `OBJCTRA` or `OBJCTDEC`. Those keywords are the only header fields read. The parser accepts three numbers separated by spaces. `getDEC` applies the minutes and seconds with the opposite sign only when the degree value is less than zero. For a header value `-0 30 00` or `-00 30 00`, the degree parses as negative zero, the comparison is false, and the result is +0.5° rather than −0.5°. `getRA` converts seconds with the factor 0.00417; 15/3600 is 0.0041666…. At the end of a minute of right ascension the difference is under an arcsecond, which matters little for a hint and is still the wrong factor.

SpacePixels builds the request in `PlateSolveService.solve` with scale 0.1–180°, downsample 2 and radius 10, and leaves the center unset. A frame whose header lacks `OBJCTRA` and `OBJCTDEC`, or writes them with colons or unit letters, is therefore uploaded as a 10° search around RA 0h, Dec 0°. `blindSolve` also sets `publicly_visible` to `y`. SpacePixels does not use `blindSolve`; it sends `n`.

`customSolve` writes the session and any parsed center into the caller's request object.

**Risk and proposal.** Most unsolved frames are not in that 10° patch. The service searches there until it fails or the fifteen-minute budget ends, which is the wait in JP-02 spent on a constraint the user did not choose. A field between 0° and −1° declination, written in the usual `-00 30 00` form, is hinted to the northern hemisphere. An explicit center at 0h or on the equator cannot be told from "leave this unset". Sending `positional_error` 0 replaces the server's default of 1.

Use nullable fields, or a builder that records which fields were set. Serialize only those fields. Keep a caller-supplied 0. Treat a missing pointing header as a blind solve: do not send `center_ra`, `center_dec` or `radius`. Parse sexagesimal with an explicit sign, including negative zero hours and degrees, and accept the colon form in common use. Use 15/3600 for right-ascension seconds. Leave the caller's request unchanged; build the submitted copy inside the client. Default `publicly_visible` to `n` on `blindSolve`, and let a caller opt in to a public submission.

SpacePixels should keep sending `n`, scale and downsample. It should send a radius only together with a center this library actually parsed. That application change waits on the request contract above.

**Acceptance.** A unit test of the JSON body: omitted center and radius are absent; an explicit 0 is present; `positional_error` is absent unless set. Header fixtures for `12 34 56.7`, `12:34:56.7`, `-00 30 00.0` and a missing keyword. The southern fixture stays negative. A reused request object is unchanged after `customSolve`. `blindSolve` JSON contains `publicly_visible` of `n` unless the caller set `y`.

## JP-04. Take the API key from the caller

**Evidence.** `AstrometryDotNet.DEFAULT_API_KEY` is the literal `XXXXXXXX`, introduced in commit `711bdbe`. The Astrometry.net API documentation uses the same sort of placeholder in its login example. It is not an account key. [README.md](README.md) tells callers that the no-argument constructor uses a built-in guest key. SpacePixels constructs `new AstrometryDotNet()` and has no API-key field on its application settings. `login` posts that constant. A rejected login throws before the upload, which SpacePixels already surfaces through `astrometryNetFailure`.

**Risk and proposal.** Online solving from the application fails at login for every frame. Embedding a working shared key in the jar would put every user on one account and would publish that account's key in source control.

Require a key. A blank key fails in the constructor, or at `login`, with a message that says the key is missing. Remove the placeholder constant and the guest-key sentence from the README. SpacePixels gains a setting on the astrometry page, stored with the other application preferences, and passes it to `AstrometryDotNetConfig`. The log line that prints the login JSON currently includes the key at `FINE`; stop logging the key.

The setting is an application change. It is useful only together with this library change, because today's constructor cannot accept a key without the caller knowing to avoid the no-argument form. The no-argument constructor can remain as a deprecated adapter that fails with the same clear error, so an old call site does not silently keep using `XXXXXXXX`.

**Acceptance.** Login with an empty key fails before any HTTP call, in a unit test. A test double receives the key the caller supplied. README and the SpacePixels manual describe where the observer gets a key and where it is stored. No key value is added to the repository.

## JP-05. Close the client with the session that created it

**Evidence.** The no-argument client creates a fixed pool of three threads and an `HttpClient`. `Executors.newFixedThreadPool` uses non-daemon threads. `close` calls `shutdownNow` on the pool when this instance created it, and does not wait for the threads to finish. It does not close the `HttpClient`. On the Java 21 baseline the README asks for, `HttpClient` implements `AutoCloseable`.

SpacePixels constructs one client inside every `ImageProcessing`, via `PlateSolveService`. [ImageProcessing.close](../SpacePixels/src/main/java/eu/startales/spacepixels/util/ImageProcessing.java) shuts down a different executor. Nothing calls `AstrometryDotNet.close`. Each import builds a new processor and closes the previous one.

**Risk and proposal.** The pool threads are live roots. After the processor is closed and dropped, those three threads and the client they keep reachable stay for the life of the process. A second import adds another three. The HTTP client's own threads follow the same pattern while the client stays open.

`close` should shut the pool down, await termination for a bounded interval, and close the `HttpClient` when this instance created it. A caller-supplied executor stays the caller's, as the config type already says. An in-flight solve is cancelled as in JP-02 before the pool stops, so `shutdownNow` is not the only signal.

SpacePixels calls that `close` from `ImageProcessing.close`. Until JP-02 exists, closing may interrupt a solve that is still running; the application already refuses a second long job, and close runs when the session is replaced or the window exits.

**Acceptance.** Open a client, run a solve against a local stub, close, and confirm the pool's threads are gone and a second close is harmless. Construct two clients and close one; the other still completes. A SpacePixels-level test, or a focused lifecycle test, that opens two processors and closes the first shows a single live plate-solve pool.

## JP-06. Let a known field shorten the search

**Evidence.** Every ASTAP invocation is:

`astap -f <image> -r 360 -z 0 -fov 0 -wcs -annotate`

ASTAP documents `-r` as the radius, in degrees, of the search around the start position. Its examples use radii such as 30 and 50. It reads pointing and field of view from a FITS header when the command leaves them unset. Its command-line parser, in `astap_command_line.lpr` for the 2025.10.08 sources, treats the presence of `-fov` as a reason to skip that header calculation and to store the given value. The same manual describes `-fov 0` as an automatic mode. `-annotate` writes `<name>_annotated.jpg` beside the frame. SpacePixels deletes that jpeg during cleanup and does not display it. The library's two-minute ASTAP budget includes this work.

The README says the library reads FITS headers such as `OBJCTRA`, `OBJCTDEC` and focal length and uses them to speed the solve. The ASTAP path reads no header. The Astrometry.net path reads only those two coordinate keywords, and only inside `customSolve` (JP-03). Focal length, pixel size and plate scale are never read.

On the HTTP side the per-request timeout is also the upload budget: `HttpRequest.timeout` is `requestTimeout`, default 60 seconds, and the same duration is the connect timeout. A 61-megapixel 16-bit frame is on the order of 120 MB before compression and headers. The upload is one request. A slow link can exhaust 60 seconds while the 15-minute solve budget is untouched. SpacePixels' large-frame notes (61 MPix in the manual and in D04) are this case.

**Risk and proposal.** `-r 360` asks for a whole-sky search even when the header has a position, so a large frame spends the two-minute budget inside ASTAP and then hits JP-01. Annotation adds a full-size jpeg the application throws away. A 60-second cap on the upload fails a large frame that would have solved once the bytes were accepted.

Make radius, right ascension, declination, field of view and annotation parameters of the ASTAP call. For a FITS file, the default command omits `-r` and `-fov` so ASTAP can use its own header rules, and omits `-annotate` unless the caller asks. Keep `-z 0` and `-wcs`. Where the caller, or a header, supplies a center and a scale, pass a modest radius and `-fov` explicitly and record the values in the result metadata.

Split the HTTP timings: connect timeout, upload timeout, and the existing poll and overall solve budget. The upload default has to cover a large FITS file; the status poll can stay short. Optionally derive a degree scale from focal length and pixel size when those keywords exist, and send it as `scale_lower` / `scale_upper` only when the caller did not set a scale. Leave SpacePixels' current 0.1–180° scale in place until that hint is trusted; a wrong scale is a failed solve.

The README's focal-length sentence should be rewritten when this lands, so it describes the headers that are actually read.

**Acceptance.** A command-capture test: default FITS launch has no `-r 360` and no `-annotate`; an explicit radius and annotation appear when requested. A local HTTP stub: an upload that takes longer than the status timeout still completes when the upload budget allows it, and a connect failure still fails promptly. Compare a hinted and an unhinted ASTAP command on one real frame before changing SpacePixels' defaults, and record the wall time.

## JP-07. One result contract

**Evidence.** `PlateSolveResult` is documented as immutable. The map stored by the constructor is the map returned to the caller, and both backends pass a mutable `HashMap`.

ASTAP success uses a null failure reason and the ini's warning. Astrometry.net success uses an empty string for both the failure reason and the warning. A solver rejection, a solve-budget timeout and an HTTP error inside `performSolve` all become a failed `PlateSolveResult` whose reason is a sentence. An ASTAP timeout is instead an `IOException` from the future. Cancellation has no state (JP-02).

SpacePixels classifies network failures in `PlateSolveService.astrometryNetFailure`, by exception type: unreachable host, HTTP timeout, or the first message in the cause chain. That helper wraps only the `login` / `customSolve` call that submits the future. `performSolve` catches `IOException` and `RuntimeException` and returns a failed result, so a drop during upload or polling never reaches the classifier. The dialog then shows the library sentence.

**Risk and proposal.** Two callers can disagree about an empty reason versus a null one. A caller can put entries into a result another viewer still holds. The application's careful network text applies to the start of the solve and not to the long wait.

Keep `PlateSolveResult` and `isSuccess`. Copy the metadata map in the constructor and return an unmodifiable view. Use null failure and warning fields on success for both backends. Carry a small reason category on failure and cancellation: missing key, unreachable, timed out, cancelled, rejected by the solver, unexpected response, and the original throwable where there is one. The existing string stays as the human-readable detail. Compatibility adapters may still throw from `Future.get` for the ASTAP path until callers move; new methods return the result, including timeout and cancellation, so SpacePixels can use one `showSolveOutcome` path.

`astrometryNetFailure` can then key off the category for the whole operation, including the upload and the poll. Wording stays in SpacePixels.

**Acceptance.** A successful result from both backends has a null failure reason, and its map rejects modification. A stubbed HTTP timeout, a connection refusal and a solver `"failure"` status produce different categories and preserve the cause. SpacePixels' existing failure-message test grows one case for an error that happens after submission.

## JP-08. Encode the Java target and test the contracts offline

**Evidence.** [README.md](README.md) requires Java 21. [build.gradle](build.gradle) sets no toolchain and no release flag, so the bytecode is whatever JDK happens to run Gradle. The README's dependency snippet asks for `jplatesolve:1.0.0`; the project version is `1.0.1`. Javadoc is generated with doclint disabled.

The only unit tests are the three ini-reader cases. There is no test for coordinate parsing, the JSON body, the API-key check, process lifetime, cancellation or HTTP status handling. `integrationTest` logs into the live service and uploads real images. With the placeholder key those tests cannot pass. They are correctly excluded from the default `test` task. `check`, which the Central bundle runs, therefore never exercises the online client.

SpacePixels resolves this library from Maven Central unless `useLocalJPlateSolveBuild` (or `USE_LOCAL_JPLATE_SOLVE_BUILD`) is set. A composite build does not prove the published jar.

**Risk and proposal.** A contract fixed in JP-01–07 can regress with nothing in `test` to see it. A release built on a newer JDK than 21 can be unreadable by the application. The README currently describes a guest key and header hints the code does not implement (JP-04, JP-06).

Set the toolchain and `--release` to 21, matching the README and SpacePixels. Add the unit tests named in the acceptance sections above, using a stand-in process and a local HTTP stub. Keep `integrationTest` opt-in, and skip it with a clear message when no key is configured. Re-enable doclint for the public types, or fix the tags that fail, rather than leaving it off as the permanent setting. When a change adds a method SpacePixels will call, name the library version, keep the previous version working for the current application, and run SpacePixels tests against that version in both the composite build and the published-jar arrangement.

**Acceptance.** `test` passes on JDK 21 with no network. `integrationTest` does not run as part of `test` or `check`. The README version, the Java requirement and the described solve inputs match the build and the code.

## What SpacePixels does after a library change

SpacePixels already depends on this library for one frame at a time. The application work below starts when the corresponding library item is in a version the application builds against. Behaviour of the current published jar stays as it is until then.

| Library item | Application follow-up | Related application work |
|---|---|---|
| JP-01 | Keep deleting `.ini`, `.wcs` and the annotated jpeg after the solve returns. Cleanup is no longer what stops ASTAP. | The staged WCS write stays as it is. |
| JP-02 | Mark `PLATE_SOLVE` cancellable. Forward solver phases to the job progress. Pass a solve budget. Measure Cancel on a large frame. | D04, the plate-solve row in `JobKind` |
| JP-03 | Send a radius only with a center the library parsed. Keep `publicly_visible=n`. | `PlateSolveService.solve` |
| JP-04 | Add the observer's Astrometry.net key to application settings and pass it on the client. | Astrometry configuration page, manual |
| JP-05 | Call `AstrometryDotNet.close` from `ImageProcessing.close`. | Session replacement and window exit |
| JP-06 | Adopt a hinted ASTAP command and a longer upload budget after the timings in JP-06 are written down. | Large-frame plate solve |
| JP-07 | Classify upload and polling failures with the same categories as login failures. | `astrometryNetFailure`, `showSolveOutcome` |

Plate solve remains a SpacePixels job. This library does not gain a Swing dialog, a progress window or a FITS writer.

## Ordering

A practical sequence, each item still needing its own decision:

1. **JP-04 and JP-03.** Online solving can succeed login, and a frame without a pointing header is no longer confined to the origin. Both are observable with a stub, before any ASTAP install.
2. **JP-01.** Stops orphaned ASTAP processes and stale solutions. Independent of the online client.
3. **JP-02, then the SpacePixels cancel follow-up.** Depends on JP-01 for the process kill. This is the wait the application already documents.
4. **JP-05, JP-07, JP-06, JP-08.** Lifecycle and the result categories can land with JP-02. The search-radius and upload-budget changes want a before-and-after timing on a real large frame. The toolchain and the offline tests should arrive with the first of these changes, not after all of them.
