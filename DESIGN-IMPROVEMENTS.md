# Design improvements

This file lists design, refactoring and code-quality work for JPlateSolve: changes to how the library's code is organised, not to what it does. New or changed solver functionality is in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). The current behaviour is described in [README.md](README.md) and the development rules in [DEVELOPER.md](DEVELOPER.md).

Every item is a proposal until the maintainer decides on it. When an item is done, describe the result in README.md and remove it from this file; git history keeps the record.

**How to use the issue reviews (2026-10-24).** Before implementing an item, consider and evaluate its review comments against the current code and contracts. Record which comments are accepted, adjusted or rejected, why, and the relevant verification with the change. They are review input, not authorization to implement the proposal or automatic proof of a current bug. This was a static review; no solver processes or live uploads were run. Treat changes to cancellation, cleanup, metadata and failure behaviour as contract decisions, even when the scientific solution is unchanged.

## Code review of 2026-10-23

A fresh review of the library (about 2,300 lines of main code and 700 of tests), against the principles in [DEVELOPER.md](DEVELOPER.md#principles).

**Overall.** The library is small and its design is right for its job:
- two backends behind one result type;
- `PlateSolveOperation`, which owns cancellation, deadlines and the clean-up of failed solves;
- a streaming upload;
- offline tests with a child process and a loopback HTTP server.

`PlateSolveOperation` is a small framework in its own right (status, phase, progress, outcome, work, clean-up and context). It earns that, because a cancelled solve must stop a process or an upload and restore files. The findings are a few leftovers and two inconsistencies.

### Recommended changes

| ID | Change | Priority | Size |
|---|---|---|---|
| [JP-09](#jp-09-remove-dead-code) | Remove dead code | High | Small |
| [JP-10](#jp-10-one-api-style-the-operation-api) | One API style: the operation API | Medium | Small |
| [JP-07](#jp-07-a-consistent-result-contract) | A consistent result contract | Medium | Small |
| [JP-04](#jp-04-redact-session-tokens) | Redact session tokens | Medium | Small |
| [JP-08](#jp-08-offline-contract-tests-and-javadoc-checks) | Offline contract tests and Javadoc checks | Low | Small |
| [JP-11](#jp-11-upgrade-the-dependencies) | Upgrade the dependencies | Medium | Small |

### JP-09. Remove dead code

`SubmissionProgressResponse` is never used: the client reads the submission status through `firstJobId`. `SubmitFileBodyPublisher.buildMultipartData`, the in-memory multipart helper described as a "diagnostic path", has no caller either. Delete both.

**Issue review (2026-10-24; evaluate before implementation).**
- Repository searches support the lack of internal callers, so this is a reasonable narrow cleanup. Check integration sources and README examples too, and document removal of public types/methods even when no external users are currently known.
- Keep the streaming upload path and its cancellation coverage. Do not replace the removed diagnostic helper with a new full-file byte array in tests or production; large uploads must remain streamed.

### JP-10. One API style: the operation API

**Problem.** Each backend has two entry styles:
- the operation API: `ASTAPInterface.startSolve`, `AstrometryDotNet.startBlindSolve` and `startCustomSolve`;
- thin `Future` adapters over it: `ASTAPInterface.solveImage` (two overloads), `AstrometryDotNet.blindSolve` and `customSolve`.

SpacePixels uses only the operation API. The adapters are used only by the live integration tests and one unit test of `asFuture`. They also behave differently. `asFuture(failuresAsResults)` is called with `false` for ASTAP and `true` for Astrometry.net, so the same `Future.get()` throws on one backend and returns a failed result on the other.

**Maintainer's answer (2026-10-23).** JPlateSolve has no users outside SpacePixels today, but it was made so that others can use it one day.

**Proposal.** Keep only the operation API:
- Nobody depends on the adapters yet, so now is the cheapest moment to settle on one style.
- A future user is better served by one cancellable API than by two that fail differently.

Steps:
1. Move the integration tests to the operation API.
2. Remove `asFuture` and the four adapter methods.
3. Rewrite the README examples around `startSolve`, `startBlindSolve` and `operation.completion()`. They currently start with the adapters.

A blocking call stays one line: `operation.completion().toCompletableFuture().get()`.

**Issue review (2026-10-24; evaluate before implementation).**
- **The migration must explain outcome handling, not just waiting.** `completion()` resolves normally with an `Outcome` for rejection, failure, timeout and cancellation. A successful `get()` does not mean the image was solved; examples must inspect status/result/cause and keep remote rejection distinct from transport or cleanup failure.
- Cancellation must go through `operation.cancel()`. The future obtained from the minimal completion view is not the legacy adapter that forwards cancellation to the operation. Show cancel-then-await-cleanup explicitly, including executor/client ownership and shutdown.
- Move cancellation tests onto the operation API before removing the adapters, retaining assertions that completion follows local process/HTTP cleanup and that cleanup failure is visible. Do not imply that local cancellation cancels the remote Astrometry.net job.

### JP-07. A consistent result contract

The earlier review narrowed this to three things that are still open:

- **`PlateSolveResult`** is documented as immutable, but returns the caller's mutable `HashMap`. Copy the map in the constructor and return an unmodifiable view.
- **On success,** ASTAP sets the failure reason to null and Astrometry.net sets it to an empty string. Use null for "absent" on both, and keep a real warning.
- **Protocol fixtures** for multiple jobs, malformed JSON, a missing submission ID and an HTTP error status. Fixtures for nova's empty and `[null]` job states already exist.

Add a failure-category enum only when a caller needs to act on it, for example to retry after a network failure. SpacePixels words failures from exception types and has no retry.

**Issue review (2026-10-24; evaluate before implementation).**
- Copy the supplied map before wrapping it; an unmodifiable view over the caller's map is still mutable through the caller. Test both mutation of the original map after construction and attempted mutation through the accessor.
- Decide the null contract explicitly. Existing construction paths can supply a null map (including the legacy adapter), and an unconditional `Map.copyOf` would reject it and null entries. Align those paths with JP-10 and document whether absent metadata becomes an empty map or is rejected. Clarify when the documented `source` entry is guaranteed.
- Normalize absent failure/warning text without discarding a real warning. Keep `REJECTED`, `FAILED`, `TIMED_OUT` and `CANCELLED` outcomes distinct. Multiple-job fixtures should document the current first-non-null job selection; choosing a different successful job would be a protocol policy change, not just a fixture.

### JP-04. Redact session tokens

The anonymous default key, the caller's own key, the README and the reason for a refused login are done. What remains: `LoginResponse.toString()` and `SubmitFileRequest.toString()` print the session token. Redact it, and add a log-handler test that sees neither a key nor a session token.

**Issue review (2026-10-24; evaluate before implementation).**
- Redact diagnostic representations, not the fields serialized into authenticated requests. Verify the loopback server still receives the expected session token while `toString()` output and captured logs do not contain it.
- Test both named `toString()` methods directly, as a log-handler test alone could pass when those methods are never logged. Cover success and refused-login/error paths, enabling diagnostic levels where needed, and restore logger handlers/levels after the test. Use synthetic credentials only.

### JP-08. Offline contract tests and Javadoc checks

The Java target (21), the offline tests of both backends and the configurable service root are done. Still to do:
- add the fixtures of JP-03 (in FEATURE-REQUESTS.md), JP-04 and JP-07 as those changes land;
- let the live `integrationTest` say whether it logged in anonymously or with a supplied key;
- re-enable doclint for public types (`build.gradle` sets `Xdoclint:none`), fixing the remaining tags instead of suppressing the check.

**Issue review (2026-10-24; evaluate before implementation).**
- Extend the existing loopback and process fixtures for public contracts rather than asserting private method structure. Keep deadline accounting, queued cancellation, cleanup failures and borrowed-executor ownership covered alongside protocol errors.
- Report only whether a key was supplied or anonymous login was used, never its value or the session token. Keep live checks opt-in and sanitize any uploaded copy; doclint and ordinary builds must not trigger uploads.
- Verify doclint through the actual Javadoc/publication tasks and compile the operation-based README examples after JP-10, so documentation changes remain usable by a consumer.

### JP-11. Upgrade the dependencies

Part of SpacePixels' [SP-18](../SpacePixels/DESIGN-IMPROVEMENTS.md#sp-18-upgrade-the-dependencies), which holds the version table, the risks and the verification for all three repositories. For JPlateSolve:
- **Gradle wrapper:** 8.12 to 9.8.2. This is the largest step of the three repositories. Fix the deprecation warnings first (`--warning-mode all`).
- **nom-tam-fits:** 1.21.2 to 1.23.0. JPlateSolve uses it only in the Astrometry.net client, and the 1.22 to 1.23 changes are about compressed images.
- **Gson:** 2.13.2 to 2.14.0.
- **Unchanged:** JUnit stays at 4.13.2, and the release stays at Java 21 for outside users.

The offline tests must pass. Run the live integration tests once.

**Issue review (2026-10-24; evaluate before implementation).**
- Do not infer that nom-tam-fits is irrelevant because this library does not decode compressed pixels. `AstrometryDotNet.updateFromFitsHeader` parses FITS headers before upload, and SP-18 identifies changed exception handling for malformed numeric headers. Add focused valid/malformed-header fixtures and check how failures reach the operation outcome. Keep the wider compressed-pixel comparison in SpacePixels, where conversion occurs.
- Recheck versions and release notes when implementing. Keep the wrapper step separate from library changes, verify publication/Javadoc and Java 21 compatibility, and run the offline contracts before sanitized opt-in live checks. Compare live solutions semantically; service responses and timings need not be byte-identical.

## Removed from the previous plan (2026-10-23)

- **The completed records in `docs/archive`.** Removed; git history keeps them.
- **JP-03 and JP-06.** Moved to [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md): they change what is sent to the solvers.
