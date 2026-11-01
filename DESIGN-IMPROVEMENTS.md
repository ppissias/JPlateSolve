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
| [JP-08](#jp-08-offline-contract-tests-and-javadoc-checks) | Offline contract tests and Javadoc checks | Low | Small |

### JP-08. Offline contract tests and Javadoc checks

The Java target (21), the offline tests of both backends and the configurable service root are done. Still to do:
- re-enable doclint for public types (`build.gradle` sets `Xdoclint:none`), fixing the remaining tags instead of suppressing the check.

**Issue review (2026-10-24; evaluate before implementation).**
- Extend the existing loopback and process fixtures for public contracts rather than asserting private method structure. Keep deadline accounting, queued cancellation, cleanup failures and borrowed-executor ownership covered alongside protocol errors.
- Report only whether a key was supplied or anonymous login was used, never its value or the session token. Keep live checks opt-in and sanitize any uploaded copy; doclint and ordinary builds must not trigger uploads.
- Verify doclint through the actual Javadoc/publication tasks and compile the operation-based README examples after JP-10, so documentation changes remain usable by a consumer.

## Removed from the previous plan (2026-10-23)

- **The completed records in `docs/archive`.** Removed; git history keeps them.
