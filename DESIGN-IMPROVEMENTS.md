# Design improvements and review decisions

Reviewed against the source tree on 2026-10-21, at commit `7b5cb30`. This is the decision list for JPlateSolve. It holds **pending work and open decisions**. The library is the plate-solving engine behind [SpacePixels](../SpacePixels): local solves through ASTAP, and online solves through the Astrometry.net HTTP API.

The same kind of list for the detection engine is [JTransient's design list](../JTransient/DESIGN-IMPROVEMENTS.md). The application list is [SpacePixels' design list](../SpacePixels/DESIGN-IMPROVEMENTS.md). Completed process ownership, cancellation and client lifecycle work (JP-01, JP-02 and JP-05) is recorded in [the October archive](docs/archive/design-improvements-completed-2026-10.md). SpacePixels now adopts that API in version 1.0.1.

## Status and scope

**Review feedback (2026-10-21, commit `bc98435`).** The priorities and separation between library and application responsibilities are sound. The notes below identify corrections and contracts to settle before implementation; they do not approve or implement the proposals. Source and build configuration were inspected, including the relevant SpacePixels call sites. No live solve, process experiment or test suite was run for this feedback; the two checks described below belong to the original review.

**Proposed** is an idea without an implementation decision. Priority is separate from authorization. High priority means the contract should be settled before the affected use grows. It does not by itself authorize a change, and a source defect here is not a measurement on a particular night's data.

- **When an item is done, declined or retired,** describe the resulting behaviour in [README.md](README.md) and move the decision and the evidence to an archive. Leave this file as the open list.
- **One change at a time.** A process-lifetime fix (JP-01) and a request-shape fix (JP-03) can be discussed separately. SpacePixels adopts a new method only after the library version that contains it is named.

Two checks were executed while reading the code. With Gson 2.13.2, a request object left at the primitive defaults serializes as `center_ra`, `center_dec`, `radius` and `positional_error` all present and numeric, including zeros. `Float.parseFloat("-0")` and `Float.parseFloat("-00")` are negative zero, and negative zero is not less than zero. ASTAP itself was not run for this review.

## What to preserve

- **A small engine with two backends and one result.** Callers solve through [ASTAPInterface](src/main/java/io/github/ppissias/jplatesolve/astap/ASTAPInterface.java) or [AstrometryDotNet](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/AstrometryDotNet.java) and read a [PlateSolveResult](src/main/java/io/github/ppissias/jplatesolve/PlateSolveResult.java). The library stays free of Swing, FITS writing and session folders. SpacePixels owns the header update and cleanup of successful solver files; JPlateSolve owns rollback of failed or cancelled local solves.
- **The caller chooses the waiting thread.** Both entry points expose a cleanup-aware operation and retain legacy `Future` adapters. SpacePixels already waits on its plate-solve worker, in [PlateSolveTask](../SpacePixels/src/main/java/eu/startales/spacepixels/tasks/PlateSolveTask.java). Keep that split.
- **The ASTAP launch shape that is already right.** The executable is started with an argument array from the executable's directory, so a path with spaces needs no shell and the star database beside the program can be found. `-wcs` asks for the FITS-shaped header SpacePixels imports.
- **The streaming upload.** [SubmitFileBodyPublisher.getBodyPublisher](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/util/SubmitFileBodyPublisher.java) sends the file from disk. The in-memory multipart helper is a separate diagnostic path. Keep the solve on the streaming path.
- **Timeouts that a caller can already set.** The ASTAP reader takes a `Duration`. [AstrometryDotNetConfig](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/AstrometryDotNetConfig.java) takes a per-request timeout, a solve timeout and a poll interval. The gap is that the defaults, and the SpacePixels call, do not use that room. See JP-02 and JP-06.
- **Private uploads from SpacePixels.** [PlateSolveService](../SpacePixels/src/main/java/eu/startales/spacepixels/util/PlateSolveService.java) sends `publicly_visible=n`. Keep that. The library's own blind-solve default is a separate question in JP-03.
- **The existing ini checks.** [ASTAPSolveResultsReaderTest](src/test/java/io/github/ppissias/jplatesolve/astap/ASTAPSolveResultsReaderTest.java) covers a successful ini, a failed ini and a missing file. Extend that style for the new contracts. Live Astrometry.net calls stay on `integrationTest`, off the default `test` task.

## Findings and priorities

| ID | Priority | Status | Design improvement | First useful result |
|---|---|---|---|---|
| JP-03 | Medium | Proposed | Send only the constraints the caller set, and parse sexagesimal signs | A frame with no pointing header is sent as a blind solve. A declination of −0° 30′ stays south of the equator. |
| JP-04 | Low | Proposed | Keep the anonymous login, and document it and the caller's key correctly | The README says how anonymous use and a caller's key work. A refused login says why. |
| JP-06 | Medium | Proposed | Let a known field shorten the search, and size timeouts for a large frame | A 61-megapixel frame can carry a radius, a scale and an upload budget. |
| JP-07 | Medium | Proposed | One result contract for success, rejection, timeout and cancellation | The application can classify a failure without reading prose, including failures after the upload has started. |
| JP-08 | Medium | Proposed | Encode the Java target and test the contracts offline | The behaviours above are checked on the declared JDK, without a live call to Astrometry.net. |



## JP-03. Send only the constraints the caller set

**Evidence.** [SubmitFileRequest](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/SubmitFileRequest.java) stores coordinates, radius, scale and `positional_error` as primitive `float`s. The primitive default is zero. Gson writes those zeros. A request that sets only a session and a radius of 10 serializes with `center_ra` 0, `center_dec` 0, `radius` 10 and `positional_error` 0.

The Astrometry.net upload API uses `center_ra`, `center_dec` and `radius` together as a known position and a search radius. `positional_error` defaults to 1 when it is absent. The official client omits a field it was not given.

[AstrometryDotNet.customSolve](src/main/java/io/github/ppissias/jplatesolve/astrometrydotnet/AstrometryDotNet.java) treats a center of zero as "not set" and may overwrite it from `OBJCTRA` or `OBJCTDEC`. Those keywords are the only header fields read. The parser accepts three numbers separated by spaces. `getDEC` applies the minutes and seconds with the opposite sign only when the degree value is less than zero. For a header value `-0 30 00` or `-00 30 00`, the degree parses as negative zero, the comparison is false, and the result is +0.5° rather than −0.5°. `getRA` converts seconds with the factor 0.00417; 15/3600 is 0.0041666…. At the end of a minute of right ascension the difference is under an arcsecond, which matters little for a hint and is still the wrong factor.

SpacePixels builds the request in `PlateSolveService.solve` with scale 0.1–180°, downsample 2 and radius 10, and leaves the center unset. A frame whose header lacks `OBJCTRA` and `OBJCTDEC`, or writes them with colons or unit letters, is therefore uploaded with a center of RA 0h, Dec 0° and a radius of 10°. `blindSolve` also sets `publicly_visible` to `y`. SpacePixels does not use `blindSolve`; it sends `n`.

The 1.0.1 cancellation work snapshots the custom request before submitting it; caller mutation is already fixed. Numeric omission and coordinate parsing remain open.

**Live test (2026-10-21, before 1.0.1).** The M5 test frame (`L_2020-06-06_…`, site cards blanked in a copy) was uploaded twice with SpacePixels' request and the anonymous key. With its header pointing, the body carried `center_ra` 229.633, `center_dec` 2.067, `radius` 10 and `positional_error` 0, and the frame solved in 15.7 s. With `OBJCTRA` and `OBJCTDEC` blanked, the body carried `center_ra` 0, `center_dec` 0, `radius` 10 and `positional_error` 0. The frame still solved, to the same solution (RA 229.646°, Dec +2.080°, 2.543″/px), in 26.4 s. The zeros are sent as described above, but the server did not confine that search to the origin; why it ignored the zero center is not established. The declination sign was not tested; that needs a southern frame within a degree of the equator.

**Risk and proposal.** The feared failure, a search kept to a 10° patch at the origin until the fifteen-minute budget ends, did not occur in the live test; the cost observed was a slower solve. That outcome depends on server behaviour this library does not control. A field between 0° and −1° declination, written in the usual `-00 30 00` form, is hinted to the northern hemisphere. An explicit center at 0h or on the equator cannot be told from "leave this unset". Sending `positional_error` 0 replaces the server's default of 1.

Use nullable fields, or a builder that records which fields were set. Serialize only those fields. Keep a caller-supplied 0. Treat a missing pointing header as a blind solve: do not send `center_ra`, `center_dec` or `radius`. Parse sexagesimal with an explicit sign, including negative zero hours and degrees, and accept the colon form in common use. Use 15/3600 for right-ascension seconds. Leave the caller's request unchanged; build the submitted copy inside the client. Default `publicly_visible` to `n` on `blindSolve`, and let a caller opt in to a public submission.

SpacePixels should keep sending `n`, scale and downsample. It should send a radius only together with a center this library actually parsed. That application change waits on the request contract above.

**Review feedback — validate the whole hint.** Apply omission tracking to every optional numeric field, including scale bounds and downsample, not just the center. Define what happens when only one coordinate is present or parseable: do not combine it with an invented zero. Preserve explicit caller coordinates, validate finite values and ranges, and reject invalid explicit inputs with a clear error; malformed optional header hints can fall back to blind solving. Include repeated whitespace, missing DEC, seconds-only negative DEC (`-00 00 30`), NaN/infinity and invalid scale bounds in acceptance. Changing public getters from `float` to `Float` breaks compiled callers; keep primitive accessors with additional presence methods, or introduce a new request type and an explicit migration plan.

**Acceptance.** A unit test of the JSON body: omitted center and radius are absent; an explicit 0 is present; `positional_error` is absent unless set. Header fixtures for `12 34 56.7`, `12:34:56.7`, `-00 30 00.0` and a missing keyword. The southern fixture stays negative. A reused request object is unchanged after `customSolve`. `blindSolve` JSON contains `publicly_visible` of `n` unless the caller set `y`.

## JP-04. Keep the anonymous login, and document it and the caller's key correctly

**Evidence (live, 2026-10-21 and 2026-10-22).** `AstrometryDotNet.DEFAULT_API_KEY` is the literal `XXXXXXXX`, introduced in commit `711bdbe`. Nova accepts it as an anonymous login: `{"status": "success", "message": "authenticated user: ", …}`, with an empty user name. A made-up key is refused with `{"status": "error", "errormessage": "bad apikey"}`. With the default key, the M5 test frame solved privately in 15.7 s through 1.0.0's `customSolve` and in 14.1 s through SpacePixels' `PlateSolveService`. On 2026-10-22 the 1.0.1 `login()` again accepted the default key, and refused a made-up key with `IOException("Astrometry.net login failed.")`. An earlier reading of this item treated the constant as a non-working placeholder and online solving as failing at login; the live logins disproved both. The README sentence added with 1.0.1, "a placeholder, not a working guest key", repeated that reading; since 2026-10-22 the README describes the anonymous default and how to pass a key.

The library already takes a caller's key: `new AstrometryDotNet(String)` and `AstrometryDotNetConfig.withApiKey`, with a blank key falling back to the guest key. Since 2026-10-21 SpacePixels offers **Anonymous** (the default, which uses the no-argument constructor) or **My API key** on its Astrometry Config tab, with a **Check** that logs in without uploading. Astrometry.net's API help says that uploads made with a user's key show up on that user's web profile. The 1.0.1 cancellation work removed the login JSON/form and raw request/response logging, so the key is no longer logged.

**Risk and proposal.** Requiring a key would break anonymous solving that works today. Keep the guest key and the blank-key fallback. Since 2026-10-22 a refused login carries the server's `errormessage` with the key redacted, e.g. "Astrometry.net login failed: bad apikey", so a caller can tell a bad key from a service error. Still open: redacting session tokens in `LoginResponse.toString()` and `SubmitFileRequest.toString()`, and the log-handler test.

**Review feedback — correction and credential handling.** The current API already accepts a key through both `AstrometryDotNet(String)` and `AstrometryDotNetConfig.Builder.withApiKey`. SpacePixels can adopt either now; requiring a key and removing the misleading guest-key documentation are the library changes. Prefer failing at login for the deprecated no-argument path if offline ASTAP sessions still construct this client eagerly. Redact both login log messages (JSON and form body), plus session tokens in request/response logging and `SubmitFileRequest.toString()`. Test with a log handler that neither a supplied key nor a session token appears. Treat placeholder rejection as an expected failure, not evidence from a live login in this review.

**Note on the review feedback (live logins, 2026-10-21 and 2026-10-22).** The feedback was written before any live login. The default key is accepted as an anonymous login, so requiring a key, and calling the guest-key documentation misleading, no longer apply. SpacePixels no longer constructs the client eagerly: it makes it at the first online solve. The redaction and log-handler points stand.

**Acceptance.** A test double receives the key the caller supplied, and a blank key logs in with the guest key. A log handler sees neither a supplied key nor a session token. A refused login carries the server's `errormessage`. The README describes the anonymous default and how to pass a key; the SpacePixels manual says where the observer gets a key and where it is stored. No real key value is added to the repository.


## JP-06. Let a known field shorten the search

**Evidence.** Every ASTAP invocation is:

`astap -f <image> -r 360 -z 0 -fov 0 -wcs -annotate`

ASTAP documents `-r` as the radius, in degrees, of the search around the start position. Its examples use radii such as 30 and 50. It reads pointing and field of view from a FITS header when the command leaves them unset. Its command-line parser, in `astap_command_line.lpr` for the 2025.10.08 sources, treats the presence of `-fov` as a reason to skip that header calculation and to store the given value. The same manual describes `-fov 0` as an automatic mode. `-annotate` writes `<name>_annotated.jpg` beside the frame. SpacePixels deletes that jpeg during cleanup and does not display it. The library's two-minute ASTAP budget includes this work.

The README says the library reads FITS headers such as `OBJCTRA`, `OBJCTDEC` and focal length and uses them to speed the solve. The ASTAP path reads no header. The Astrometry.net path reads only those two coordinate keywords, and only inside `customSolve` (JP-03). Focal length, pixel size and plate scale are never read.

On the HTTP side the per-request timeout is also the upload budget: `HttpRequest.timeout` is `requestTimeout`, default 60 seconds, and the same duration is the connect timeout. A 61-megapixel 16-bit frame is on the order of 120 MB before compression and headers. The upload is one request. A slow link can exhaust 60 seconds while the 15-minute solve budget is untouched. SpacePixels' large-frame notes (61 MPix in the manual and in D04) are this case.

**Risk and proposal.** `-r 360` asks for a whole-sky search even when the header has a position, so a large frame can exhaust the two-minute ASTAP budget. Annotation adds a full-size jpeg the application throws away. A 60-second cap on the upload fails a large frame that would have solved once the bytes were accepted.

Make radius, right ascension, declination, field of view and annotation parameters of the ASTAP call. For a FITS file, the default command omits `-r` and `-fov` so ASTAP can use its own header rules, and omits `-annotate` unless the caller asks. Keep `-z 0` and `-wcs`. Where the caller, or a header, supplies a center and a scale, pass a modest radius and `-fov` explicitly and record the values in the result metadata.

Split the HTTP timings: connect timeout, upload timeout, and the existing poll and overall solve budget. The upload default has to cover a large FITS file; the status poll can stay short. Optionally derive a degree scale from focal length and pixel size when those keywords exist, and send it as `scale_lower` / `scale_upper` only when the caller did not set a scale. Leave SpacePixels' current 0.1–180° scale in place until that hint is trusted; a wrong scale is a failed solve.

The README's focal-length sentence should be rewritten when this lands, so it describes the headers that are actually read.

**Review feedback — units, defaults and deadline correction.** Specify conversions at the backend boundary: the [ASTAP manual](https://www.hnsky.org/astap.htm) defines `-ra` in hours, `-spd` as declination plus 90 degrees, and `-fov` as image height in degrees; Astrometry.net center RA is degrees and `degwidth` describes width. Focal length and pixel size yield an angular pixel scale; deriving a field size also needs the corresponding image dimension and binned-pixel interpretation. Add an asymmetric-image fixture to catch height/width confusion. Verify omitted-option behaviour with a named installed ASTAP version, including a FITS file lacking hints, before claiming it guarantees an unconstrained search.

Already delivered with 1.0.1: the monotonic total budget includes queue time, login, header parsing, upload and polling, and caps HTTP requests and polling delays. Separate upload/connect/status budgets and a measured large-frame tuning policy remain open.

**Acceptance.** A command-capture test: default FITS launch has no `-r 360` and no `-annotate`; an explicit radius and annotation appear when requested. A local HTTP stub: an upload that takes longer than the status timeout still completes when the upload budget allows it, and a connect failure still fails promptly. Compare a hinted and an unhinted ASTAP command on one real frame before changing SpacePixels' defaults, and record the wall time.

## JP-07. One result contract

**Evidence.** `PlateSolveResult` is documented as immutable. The map stored by the constructor is the map returned to the caller, and both backends pass a mutable `HashMap`.

ASTAP success uses a null failure reason and the ini's warning. Astrometry.net success uses an empty string for both the failure reason and the warning. The new operation already distinguishes solver rejection, failure, timeout and cancellation with preserved causes. Legacy adapters keep their older result/exception distinction; metadata immutability and normalized strings remain open.

Since 2026-10-22 SpacePixels words a failed online solve for the whole asynchronous operation, including upload and polling (`AstrometryNetAccount.failureMessage`, called from `PlateSolveTask`). It works from exception types and messages; preserved causes with categories and richer protocol diagnostics remain open here.

**Risk and proposal.** Two callers can disagree about an empty reason versus a null one. A caller can put entries into a result another viewer still holds. The application's careful network text applies to the start of the solve and not to the long wait.

Keep `PlateSolveResult` and `isSuccess`. Copy the metadata map in the constructor and return an unmodifiable view. Use null failure and warning fields on success for both backends. Carry a small reason category on failure and cancellation: missing key, unreachable, timed out, cancelled, rejected by the solver, unexpected response, and the original throwable where there is one. The existing string stays as the human-readable detail. Compatibility adapters may still throw from `Future.get` for the ASTAP path until callers move; new methods return the result, including timeout and cancellation, so SpacePixels can use one `showSolveOutcome` path.

`astrometryNetFailure` can then key off the category for the whole operation, including the upload and the poll. Wording stays in SpacePixels.

**Review feedback — preserve warnings and structured protocol state.** Normalize absent warnings to null, but preserve a real warning on a successful solve; the earlier proposal to make every success warning null would discard ASTAP diagnostics. Add invalid credentials, local file/process errors and HTTP service failures to the category decision so they are not misreported as solver rejection or an unexpected response. Retain HTTP status, submission/job identifiers and causes as structured diagnostics where available.

Since 2026-10-22, `waitForJobId` reads only the `jobs` array, structurally. `[]` and `[null]` mean "not ready yet", and the first non-null id is used. A body that is not a JSON object fails with a reason. This fixed a 1.0.1 regression: the cancellation rewrite parsed the whole status into `int[]` fields before checking for `[null]`, and Gson throws on a null in a primitive array. Live, every online solve then failed with a message-less `IllegalArgumentException` once nova answered `"jobs": [null]`, which it does after each upload. Still open: define how to handle multiple jobs and completed submissions with no job. The [Astrometry.net API](https://astrometry.net/doc/net/api.html) permits zero or more jobs per submission. Fixtures for nova's empty and null job states, as recorded live, and for a non-object answer exist since 2026-10-22. Still to add: multiple jobs, malformed JSON, a missing submission ID and a non-success HTTP status. These are part of the outcome contract, not just message formatting.

**Acceptance.** A successful result from both backends has a null failure reason, and its map rejects modification. A stubbed HTTP timeout, a connection refusal and a solver `"failure"` status produce different categories and preserve the cause. SpacePixels' existing failure-message test grows one case for an error that happens after submission.

## JP-08. Encode the Java target and test the contracts offline

**Already delivered with 1.0.1 cancellation.** Java toolchain and `--release` are pinned to 21, the README names 1.0.1, and default tests exercise both solver backends with a Java child process and loopback HTTP server. The configurable service root also derives result URLs. See the archive for validation and the jar-only SpacePixels check.

**Still pending.** Add the JP-03/04/07 request, credential and protocol fixtures as those contracts land. Live `integrationTest` logs in anonymously unless an external key is supplied, and says which it used. Re-enable doclint for public types, fixing the remaining tags rather than suppressing it permanently. Keep live checks separate from `test` and `check`; offline tests need no external service (loopback networking is still used).

## What SpacePixels does after a library change

SpacePixels already depends on this library for one frame at a time. The application work below starts when the corresponding library item is in a version the application builds against. Behaviour of the current published jar stays as it is until then.

| Library item | Application follow-up | Related application work |
|---|---|---|
| JP-03 | Send a radius only with a center the library parsed. Keep `publicly_visible=n`. | `PlateSolveService.solve` |
| JP-04 | **Done 2026-10-21, with the current library:** **Anonymous** or **My API key** on Astrometry Config, passed to the client, with a login **Check**. A refused login is named for the account in use. | Astrometry configuration page, manual |
| JP-06 | Adopt a hinted ASTAP command and a longer upload budget after the timings in JP-06 are written down. | Large-frame plate solve |
| JP-07 | Classify upload and polling failures with the same categories as login failures. The wording already covers the whole operation (2026-10-22); categories follow the library contract. | `AstrometryNetAccount.failureMessage`, `showSolveOutcome` |

Plate solve remains a SpacePixels job. This library does not gain a Swing dialog, a progress window or a FITS writer.

## Ordering

1. **JP-03 and JP-04.** Correct omission/coordinate hint handling and the refused-login message; cancellation intentionally leaves these defaults unchanged. Online solving already works with the anonymous key, and the observer's key is configurable in SpacePixels.
2. **JP-07.** Normalize metadata and carry structured protocol/error diagnostics through the application.
3. **JP-06.** Tune large-frame search and upload budgets only after a measured real solve.
4. **Remaining JP-08 checks.** Add contract fixtures with each change and finish live-test key handling/doclint.
