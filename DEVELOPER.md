# Developer guide

How JPlateSolve is built, how its code is organised and the principles it follows. This guide is for developers and coding agents. Planned design work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) and planned functionality in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). How to use the library is in [README.md](README.md), and releases in [PUBLISHING.md](PUBLISHING.md).

Machine-specific paths (tools, test data, scratch folders) are in a git-ignored `LOCAL-ENVIRONMENT.txt` at the repository root (see [AGENTS.md](AGENTS.md)).

## Principles

Correct astrometric results, protection of credentials and completion of owned cleanup constrain every design change. Within those constraints, the maintainer's goals for the code, in order:

1. **Readable code.** A reader should understand a class from its name, its Javadoc and its fields.
2. **A clear design with clear boundaries.** Each piece of mutable state has one authoritative owner, and dependencies point one way. Caller-owned requests, detached operation snapshots and returned results have distinct lifetimes.
3. **No abstraction or engineering without a real need.** An interface normally needs two implementations or a test seam that is actually used; an explicit callback or ownership boundary can also justify one. A layer needs a responsibility of its own. A compatibility path needs a caller that still uses it. Remove unused machinery after identifying the contracts it protects. Fewer classes or files alone do not make a design simpler.
4. **A few chosen design patterns, applied consistently.** Reuse them when their responsibilities fit; consistency does not justify an unnecessary abstraction.

For this library:

- **A small engine with two blind backends and one result.** Callers solve through `ASTAPInterface` (local ASTAP) or `AstrometryDotNet` (the Astrometry.net HTTP API), and read a `PlateSolveResult`. Custom solving is a separate deferred feature; neither backend extracts pointing or optical-scale constraints for the caller.
- **The library owns the solve; the application owns the files.** JPlateSolve runs the solver, owns its process and HTTP calls until the solve ends, and restores the files of a failed or cancelled solve. It has no UI, writes no FITS headers and knows no sessions. SpacePixels writes the WCS into the frame and removes the files of a successful solve.
- **The caller chooses the waiting thread.** `PlateSolveOperation` reports progress, honours a total budget, can be cancelled, and completes only after its clean-up has finished. Request cancellation through `operation.cancel()` and await `operation.completion()` to join local work and cleanup. Completion returns a typed outcome normally even for cancellation, timeout or failure; callers inspect its status, result and cause. Cancelling the `CompletableFuture` obtained from the completion view does not cancel the solve. Local cancellation does not imply that the remote service has stopped a submitted job.
- **Uploads stream from disk** (`SubmitFileBodyPublisher.getBodyPublisher`).
- **Privacy.** Blind solves upload privately unless the immutable client configuration explicitly opts into public visibility. The internal wire record has only session and visibility; no caller-owned request is mutated. Anonymous Astrometry.net logins share one account, whose job list other users can see. SpacePixels therefore uploads a copy with the site and observer cards blanked and uses private visibility. Never log an API key or a session token; login-response and upload-request diagnostics redact the session field without changing authentication serialization.
- **One coordinated source set with SpacePixels and JTransient.** Change the API and its SpacePixels callers together. Document intentional public API breaks and update README examples and consumer checks; the same cleanup and failure guarantees apply to future embedding callers.

## Working rules

The shared working rules are in SpacePixels' [DEVELOPER.md](../SpacePixels/DEVELOPER.md#working-rules) and apply here too: discuss a fix first, remove the observing site from anything published, use descriptive code commits and generic documentation commits, don't rewrite history, refer to people as they/them. The two test frames in this repository may keep their `SITELAT`/`SITELONG` cards, but blank them in any copy uploaded to an external service.

Document every new Java type and method with a concise Javadoc, including ownership, units and contracts.

Consider and evaluate each issue's review comments before implementation, checking the evidence against the current code and recording the decision and relevant verification with the change. Preserve cancellation, deadlines, cleanup ordering and borrowed-executor ownership during refactoring. Changes to failure status, metadata nullability, request serialization or accepted inputs require an explicit decision and corresponding offline contract checks.

## Build and test

Use Java 21 and the Gradle wrapper.

```powershell
.\gradlew.bat test              # offline: a Java child process and a loopback HTTP server; no ASTAP, account or upload
.\gradlew.bat integrationTest   # opt-in live checks against Astrometry.net and a local ASTAP
.\gradlew.bat build
```

SpacePixels builds this checkout directly when its `useLocalJPlateSolveBuild=true` is set. After an implementation change, also run the SpacePixels tests and one real plate solve with each backend. Documentation-only reviews need link and diff checks, without live uploads. Refactorings should preserve deterministic offline requests and results; live service results and timings need semantic comparison, not byte equality. Keep integration checks opt-in and use sanitized upload copies.

**Privacy review decisions (JP-04, JP-12, JP-14).** The JP-04 review was accepted: test both diagnostic representations directly, prove that authentication still reaches the loopback server, exercise success/rejection/refused-login logs with synthetic credentials, and restore logger handlers and levels. Private visibility is an intentional default change, with explicit public opt-in preserved and no mutation of caller parameters. The JP-14 live tests use sanitized temporary copies and operation outcomes, with known-centre checks and a distinct rejection assertion. The shared sanitization helper in `src/testSupport/java` is compiled only into test source sets and checked offline, including pixel parity on the real fixtures. Live tests report account mode without credentials and leave global logging untouched. Those privacy changes preserve cancellation, cleanup and executor ownership; the blind-only scope and API migration are recorded below.

**Blind-solve and implementation-review decisions (2026-11-01; JP-03, JP-06, JP-07, JP-09, JP-10, JP-13).** The maintainer chose blind-only behavior in both projects, with custom solving deferred to JP-15/F20. JP-03 is implemented as a two-field upload record rather than a general presence/coordinate-resolution framework. Header parsing now validates readability only; signed-coordinate parsing, partial-pair policy and optical inference are deferred with custom solving. JP-06 keeps explicit whole-sky/automatic-scale ASTAP options, removes annotation generation and JPEG ownership, and separates connection, upload and login/poll limits. Shorter independent route or connection timeouts stay failures, while the monotonic total deadline remains a timeout; a connection failure never waits out a clipped request budget. The tests capture the production command, preserve older JPEGs, throttle large uploads with bounded buffers and inject errors/timeouts per route.

The JP-07 follow-ups were accepted: a successful upload envelope missing its identifier is a descriptive protocol failure without subsequent polling; absent warnings are omitted by the SpacePixels dialog. Backend results guarantee `source`, while caller-constructed results may have empty metadata. Map copying and optional-text normalization remain, with null-entry policy documented. The README now uses ASTAP's actual coordinate keys and lists intentional public API removals and timeout migration. Keep new release coordinates and SpacePixels dependencies coordinated when publishing; these source changes retain no legacy adapters. The opt-in live checks cover local and anonymous online solving on copies, with semantic coordinate/WCS checks and input/settings preservation; machine-specific evidence belongs in LOCAL-ENVIRONMENT.txt.

## The packages

| Package | What it holds |
|---|---|
| `io.github.ppissias.jplatesolve` | `PlateSolveOperation` (cancellation, deadline, clean-up) and `PlateSolveResult` |
| `…astap` | `ASTAPInterface` (process launch and ownership) and `ASTAPSolveResultsReader` (the `.ini` and `.wcs` output) |
| `…astrometrydotnet` | `AstrometryDotNet` (login, upload, polling), `AstrometryDotNetConfig`, and the JSON request and response types |
| `…astrometrydotnet.util` | The streaming multipart upload body |
