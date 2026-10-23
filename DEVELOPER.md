# Developer guide

How JPlateSolve is built, how its code is organised and the principles it follows. This guide is for developers and coding agents. Planned design work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md) and planned functionality in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). How to use the library is in [README.md](README.md), and releases in [PUBLISHING.md](PUBLISHING.md).

Machine-specific paths (tools, test data, scratch folders) are in a git-ignored `LOCAL-ENVIRONMENT.txt` at the repository root (see [AGENTS.md](AGENTS.md)).

## Principles

The maintainer's goals for the code, in order:

1. **Readable code.** A reader should understand a class from its name, its Javadoc and its fields.
2. **A clear design with clear boundaries.** Each piece of state has one owner, and dependencies point one way.
3. **A few chosen design patterns, applied consistently.**
4. **No abstraction or engineering without a real need.** An interface needs two implementations or a test seam that is actually used. A compatibility path needs a caller that still uses it. When in doubt, delete rather than wrap.

For this library:

- **A small engine with two backends and one result.** Callers solve through `ASTAPInterface` (local ASTAP) or `AstrometryDotNet` (the Astrometry.net HTTP API), and read a `PlateSolveResult`.
- **The library owns the solve; the application owns the files.** JPlateSolve runs the solver, owns its process and HTTP calls until the solve ends, and restores the files of a failed or cancelled solve. It has no UI, writes no FITS headers and knows no sessions. SpacePixels writes the WCS into the frame and removes the files of a successful solve.
- **The caller chooses the waiting thread.** `PlateSolveOperation` reports progress, honours a total budget, can be cancelled, and completes only after its clean-up has finished.
- **Uploads stream from disk** (`SubmitFileBodyPublisher.getBodyPublisher`).
- **Privacy.** Anonymous Astrometry.net logins share one account, whose job list other users can see. SpacePixels therefore uploads a copy with the site and observer cards blanked, and sends `publicly_visible=n`. Never log an API key or a session token.
- **One coordinated source set with SpacePixels and JTransient.** Change the API and its SpacePixels callers together.

## Working rules

The shared working rules are in SpacePixels' [DEVELOPER.md](../SpacePixels/DEVELOPER.md#working-rules) and apply here too: discuss a fix first, remove the observing site from anything published, use descriptive code commits and generic documentation commits, don't rewrite history, refer to people as they/them. The two test frames in this repository may keep their `SITELAT`/`SITELONG` cards, but blank them in any copy uploaded to an external service.

Document every new Java type and method with a concise Javadoc, including ownership, units and contracts.

## Build and test

Use Java 21 and the Gradle wrapper.

```powershell
.\gradlew.bat test              # offline: a Java child process and a loopback HTTP server; no ASTAP, account or upload
.\gradlew.bat integrationTest   # opt-in live checks against Astrometry.net and a local ASTAP
.\gradlew.bat build
```

SpacePixels builds this checkout directly when its `useLocalJPlateSolveBuild=true` is set. After a change, also run the SpacePixels tests and one real plate solve with each backend.

## The packages

| Package | What it holds |
|---|---|
| `io.github.ppissias.jplatesolve` | `PlateSolveOperation` (cancellation, deadline, clean-up) and `PlateSolveResult` |
| `…astap` | `ASTAPInterface` (process launch and ownership) and `ASTAPSolveResultsReader` (the `.ini` and `.wcs` output) |
| `…astrometrydotnet` | `AstrometryDotNet` (login, upload, polling), `AstrometryDotNetConfig`, and the JSON request and response types |
| `…astrometrydotnet.util` | The streaming multipart upload body |
