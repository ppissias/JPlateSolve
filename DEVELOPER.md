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

- **A small engine with two backends and one result.** Callers solve through `ASTAPInterface` (local ASTAP) or `AstrometryDotNet` (the Astrometry.net HTTP API), and read a `PlateSolveResult`.
- **The library owns the solve; the application owns the files.** JPlateSolve runs the solver, owns its process and HTTP calls until the solve ends, and restores the files of a failed or cancelled solve. It has no UI, writes no FITS headers and knows no sessions. SpacePixels writes the WCS into the frame and removes the files of a successful solve.
- **The caller chooses the waiting thread.** `PlateSolveOperation` reports progress, honours a total budget, can be cancelled, and completes only after its clean-up has finished. Request cancellation through `operation.cancel()` and await `operation.completion()` to join local work and cleanup. Completion returns a typed outcome normally even for cancellation, timeout or failure; callers inspect its status, result and cause. Cancelling the `CompletableFuture` obtained from the completion view does not cancel the solve. Local cancellation does not imply that the remote service has stopped a submitted job.
- **Uploads stream from disk** (`SubmitFileBodyPublisher.getBodyPublisher`).
- **Privacy.** Blind solves and custom requests with absent or blank visibility upload privately; an explicit `publicly_visible=y` remains available. This default is applied to the operation's detached request, leaving the caller's object unchanged. Anonymous Astrometry.net logins share one account, whose job list other users can see. SpacePixels therefore uploads a copy with the site and observer cards blanked, and sends `publicly_visible=n`. Never log an API key or a session token; login-response and upload-request diagnostics redact the session field without changing authentication serialization.
- **One coordinated source set with SpacePixels and JTransient.** Change the API and its SpacePixels callers together. Document intentional public API breaks and update README examples and consumer checks; the same cleanup and failure guarantees apply to future embedding callers.

**Current exceptions.** `PlateSolveResult` is documented as immutable but currently retains and exposes its input metadata map (JP-07). Legacy Future adapters have different failure behavior between backends and can release a cancelled wait before cleanup finishes (JP-10). These are current gaps, not contracts to copy. The principles guide changes; pending proposals do not describe completed work.

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

**Privacy review decisions (JP-04, JP-12, JP-14).** The JP-04 review was accepted: test both diagnostic representations directly, prove that authentication still reaches the loopback server, exercise success/rejection/refused-login logs with synthetic credentials, and restore logger handlers and levels. Private visibility is an intentional default change, with explicit public opt-in preserved and no mutation of caller parameters. The JP-14 live tests use sanitized temporary copies and operation outcomes, with known-centre checks and a distinct rejection assertion. The shared sanitization helper in `src/testSupport/java` is compiled only into test source sets and checked offline, including pixel parity on the real fixtures. Live tests report account mode without credentials and leave global logging untouched. Legacy adapter removal remains a separate JP-10 decision; these changes do not alter cancellation, cleanup, executor ownership or numeric-hint policy.

## The packages

| Package | What it holds |
|---|---|
| `io.github.ppissias.jplatesolve` | `PlateSolveOperation` (cancellation, deadline, clean-up) and `PlateSolveResult` |
| `…astap` | `ASTAPInterface` (process launch and ownership) and `ASTAPSolveResultsReader` (the `.ini` and `.wcs` output) |
| `…astrometrydotnet` | `AstrometryDotNet` (login, upload, polling), `AstrometryDotNetConfig`, and the JSON request and response types |
| `…astrometrydotnet.util` | The streaming multipart upload body |
