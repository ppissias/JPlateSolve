# 🌌 JPlateSolve

**JPlateSolve** is a pure Java library for automated astrometric plate solving. It provides a unified, asynchronous API to determine the exact celestial coordinates (Right Ascension and Declination) of astronomical images.

It serves as the plate-solving engine behind [SpacePixels](https://github.com/ppissias/SpacePixels) and is designed to be easily embedded into any Java-based astrophotography tool, observatory control software, or batch processing pipeline.

<!-- root-toc:start -->
## Contents

- [✨ Key Features](#-key-features)
- [⚠️ Prerequisites](#%EF%B8%8F-prerequisites)
  - [Upload privacy](#upload-privacy)
- [🚀 Quick Start Guide](#-quick-start-guide)
  - [1. Add to your project](#1-add-to-your-project)
  - [2. Example A: Local Solving via ASTAP](#2-example-a-local-solving-via-astap)
  - [3. Example B: Cloud Solving via Astrometry.net](#3-example-b-cloud-solving-via-astrometrynet)
- [Operation API and cancellation](#operation-api-and-cancellation)
  - [JP-03. Make online blind-solve requests unambiguous](#jp-03-make-online-blind-solve-requests-unambiguous)
  - [JP-06. Improve blind-solve reliability and remove unused annotations](#jp-06-improve-blind-solve-reliability-and-remove-unused-annotations)
  - [Development API migration](#development-api-migration)
  - [Offline contract tests](#offline-contract-tests)
- [🛠️ Building JPlateSolve from Source](#%EF%B8%8F-building-jplatesolve-from-source)
- [📦 Publishing to Maven Central](#-publishing-to-maven-central)
- [📄 License](#-license)
<!-- root-toc:end -->

## ✨ Key Features

JPlateSolve supports two completely different backend engines, allowing you to choose between offline speed and online convenience:

1. **Local Plate Solving (ASTAP):** Interfaces directly with a local installation of the blazing-fast [ASTAP (Astrometric STAcking Program)](https://www.hnsky.org/astap.htm) executable. Perfect for rapid, offline solving.
2. **Cloud Plate Solving (Astrometry.net):** Blind solving through the [Astrometry.net API](https://astrometry.net/doc/net/api.html), with anonymous or personal-account authentication.
3. **Blind solving only:** No caller-supplied pointing, search-radius or optical-scale constraints. FITS headers are checked for readability without interpreting their coordinate hints. Custom solving is outside the current scope; revisiting it requires a new proposal and maintainer decision.
4. **Asynchronous and Cancellable:** Solve operations expose phase progress and a completion stage that resolves after local cleanup.

## ⚠️ Prerequisites

* **Java 21 or higher:** 
* **For Local Solving:** You must have [ASTAP](https://www.hnsky.org/astap.htm) and a suitable star database installed on the host machine.
* **For Cloud Solving:** nothing to install. The no-argument client logs in anonymously with the built-in guest key (`XXXXXXXX`), which nova.astrometry.net accepts; that depends on the service continuing to accept it. To solve under your own account, pass your [Astrometry.net API key](https://nova.astrometry.net/api_help) with `new AstrometryDotNet(key)` or `AstrometryDotNetConfig.Builder.withApiKey`. Uploads made with your key show up on your Astrometry.net profile. A blank key falls back to the guest key.

### Upload privacy

Blind solves upload privately (`publicly_visible=n`). A library caller can deliberately opt into public uploads with `AstrometryDotNetConfig.builder().withPubliclyVisible(true)`. SpacePixels always uses private uploads. A public upload shares the image and its FITS metadata on Astrometry.net.

Private visibility does not remove metadata from the file. The anonymous login uses a shared account whose other users can see its jobs. Remove observing-site and observer information from a temporary copy before uploading sensitive FITS images, as SpacePixels does. JPlateSolve uploads the file supplied by its caller; it does not sanitize or modify that original. Diagnostic `toString()` values redact session tokens while authentication payloads retain them.

## 🚀 Quick Start Guide

### 1. Add to your project
If you are using Gradle, include JPlateSolve in your dependencies:
```groovy
dependencies {
   implementation 'io.github.ppissias.jplatesolve:jplatesolve:1.0.2'
}
```

### 2. Example A: Local Solving via ASTAP
ASTAP solving is lightning fast but requires the executable to be present on the local machine.

```java
import io.github.ppissias.jplatesolve.PlateSolveOperation;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astap.ASTAPInterface;

import java.io.File;
import java.time.Duration;

public class PlateSolverApp {
   public void solveLocally() throws Exception {
      File astapExe = new File("C:/Program Files/astap/astap.exe"); // Or /usr/bin/astap on Linux
      String targetImage = "C:/AstroData/light_frame.fits";

      System.out.println("Starting local solve...");

      // Starts ASTAP in the background and returns a cancellable PlateSolveOperation
      PlateSolveOperation operation = ASTAPInterface.startSolve(
              astapExe, targetImage, Duration.ofMinutes(2),
              progress -> System.out.println(progress.phase() + ": " + progress.message()));

      // Await completion (joining process cleanup)
      PlateSolveOperation.Outcome outcome = operation.completion().toCompletableFuture().get();

      if (outcome.status() == PlateSolveOperation.Status.SUCCEEDED) {
         PlateSolveResult result = outcome.result();
         System.out.println("Solve Successful!");
         System.out.println("RA: " + result.getSolveInformation().get("CRVAL1"));
         System.out.println("DEC: " + result.getSolveInformation().get("CRVAL2"));
      } else if (outcome.status() == PlateSolveOperation.Status.REJECTED) {
         System.out.println("Solve rejected: " + outcome.result().getFailureReason());
      } else {
         System.out.println("Solve failed (" + outcome.status() + "): " + outcome.cause().getMessage());
      }
   }
}
```

### 3. Example B: Cloud Solving via Astrometry.net
If the user doesn't have ASTAP, you can securely upload the image to the Astrometry.net cloud for blind solving.

```java
import io.github.ppissias.jplatesolve.PlateSolveOperation;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;

import java.io.File;

public class CloudSolverApp {
   public void solveInCloud() throws Exception {
      File targetImage = new File("C:/AstroData/light_frame.fits");

      // Your own API key, or new AstrometryDotNet() for an anonymous login
      try (AstrometryDotNet cloudSolver = new AstrometryDotNet("your-api-key")) {

         System.out.println("Uploading image and awaiting blind solve...");

         // Fires off the upload and polling in the background (private upload by default)
         PlateSolveOperation operation = cloudSolver.startBlindSolve(
                 targetImage,
                 progress -> System.out.println(progress.phase() + ": " + progress.message()));

         // Await completion (joining HTTP cleanup)
         PlateSolveOperation.Outcome outcome = operation.completion().toCompletableFuture().get();

         if (outcome.status() == PlateSolveOperation.Status.SUCCEEDED) {
            PlateSolveResult result = outcome.result();
            System.out.println("Cloud Solve Successful!");
            System.out.println("Annotated Image URL: " + result.getSolveInformation().get("annotated_image_link"));
            System.out.println("Orientation: " + result.getSolveInformation().get("orientation"));
         } else if (outcome.status() == PlateSolveOperation.Status.REJECTED) {
            System.out.println("Cloud solve rejected: " + outcome.result().getFailureReason());
         } else {
            System.out.println("Cloud solve failed (" + outcome.status() + "): " + outcome.cause().getMessage());
         }
      }
   }
}
```

## Operation API and cancellation

All solves run through the `PlateSolveOperation` API:
`ASTAPInterface.startSolve(executable, imagePath, budget, listener)` or
`AstrometryDotNet.startBlindSolve(image, listener)`.

```java
PlateSolveOperation operation = ASTAPInterface.startSolve(
        astapExe, imagePath, Duration.ofMinutes(2),
        progress -> System.out.println(progress.phase() + ": " + progress.message()));
operation.cancel();
PlateSolveOperation.Outcome outcome = operation.completion().toCompletableFuture().get();
```

- `cancel()` requests stopping; it does **not** mean resources are already released. Join `completion()` before starting conflicting work or discarding the owning session.
- Outcomes distinguish `SUCCEEDED`, solver `REJECTED`, `FAILED`, `TIMED_OUT`, and `CANCELLED`. A first accepted cancellation or timeout wins over a later solver answer. A committed answer wins a later cancellation. Cleanup failures are reported as `FAILED`, never as a clean cancellation.
- The monotonic total budget starts before queueing and includes login, FITS preparation, process launch, upload and polling. Defaults remain **2 minutes for ASTAP** and **15 minutes for Astrometry.net**. Configure cloud budgets using `AstrometryDotNetConfig`; request timeouts are capped by the remaining solve budget. Cleanup is additional: ASTAP gets a 250 ms graceful-stop interval, then forced termination and a bounded confirmation wait.
- Progress reports real phases and elapsed time, not invented frame counts or completion percentages. Listeners run on the solve worker, must return promptly, and must not call the owning client's `close()`. Listener exceptions stop the solve and still run cleanup.
- ASTAP completion follows confirmed process exit and output-reader shutdown. Both output streams are merged and drained with bounded retained diagnostics. Existing `.ini` and `.wcs` sidecars are backed up before launch; unsuccessful runs remove their generated outputs and restore those backups. Successful runs replace those sidecars. Annotated JPEGs are neither generated nor owned, deleted or advertised by the solve. Same-stem concurrent solves in this JVM are rejected, including `frame.fit` versus `frame.fits`. Do not run another application against the same output stem concurrently.
- Cloud cancellation interrupts local HTTP work and stops polling. **It does not cancel a remote job already accepted by Astrometry.net.** No remote cancellation guarantee is made.
- Client options are immutable snapshots. A blind upload contains only its authenticated session and visibility; unspecified numeric fields are absent instead of serialized as zeros.
- Close each cloud client when its session ends. close() cancels and joins its operations, shuts down HTTP resources, and joins an owned executor (shared 10-second limit, explicit failure if shutdown cannot be confirmed). Borrowed executors are never shut down; this client's queued operations are still cancelled. Starts after close are rejected.

### JP-03. Make online blind-solve requests unambiguous

Both JPlateSolve and SpacePixels use blind solving. The HTTP request sends no `center_ra`, `center_dec`, `radius`, scale bounds, downsample override or `positional_error`. The service chooses its own defaults for omitted fields. JPlateSolve never parses sexagesimal pointing or derives an optical scale; changing or corrupting those header hints cannot change its API constraints. A FITS primary header that cannot be read still fails locally before upload with a descriptive `FitsException`. The caller retains ownership of the original image and its metadata; remove sensitive metadata from a copy before uploading it.

### JP-06. Improve blind-solve reliability and remove unused annotations

ASTAP is invoked with `-ra 0 -spd 90 -r 180 -z 0 -fov 0 -wcs`: a fixed starting position, whole-sky search, automatic downsampling and automatic scale search. These explicit parameters prevent a header position or saved position/FOV from supplying a restricted search. The [ASTAP manual](https://www.hnsky.org/astap.htm#command_line) describes automatic FOV search over its supported range; blind solving is not a promise to solve every image scale. No `-annotate` or `-update` is requested. Results supply `wcs_link`, with local solved coordinates under `CRVAL1`/`CRVAL2`.

`AstrometryDotNetConfig` separates the connection timeout (default 10 seconds), upload request timeout (10 minutes), and login/poll request timeout (60 seconds). Configure them with `withConnectTimeout`, `withUploadTimeout`, and `withStatusTimeout`. An upload request limit includes both transmitting the body and waiting for the response. Missing, zero or negative durations use their defaults. Every request is capped by the remaining monotonic solve budget, which defaults to 15 minutes. Independent request/connection expiry is `FAILED` with its HTTP timeout cause; total expiry is `TIMED_OUT`. Cancellation and cleanup keep their existing ownership guarantees. The upload default allows about 120 MB at 2 Mbit/s with response headroom; slower links or larger files may require increasing both upload and total budgets.

An accepted upload without a submission identifier is a protocol failure (`FAILED`), not a rejected sky solution. A remote solver's explicit failure remains `REJECTED`.

### Development API migration

The coordinated checkout intentionally removes these public APIs; no compatibility adapters are retained. Update embedding callers when selecting the next published library version and its SpacePixels dependency together.

| Retired API | Replacement |
|---|---|
| `ASTAPInterface.solveImage` (both overloads), `AstrometryDotNet.blindSolve`, `PlateSolveOperation.asFuture` | Start an operation, inspect its typed outcome, cancel through `operation.cancel()`, and await completion for cleanup |
| `AstrometryDotNet.customSolve`, `startCustomSolve`, public `SubmitFileRequest` and its builder | `startBlindSolve(image, listener)`; custom solving is outside the current scope |
| `AstrometryDotNetConfig.Builder.withRequestTimeout`, the config getter and `AstrometryDotNet.getRequestTimeout` | Separate connection, upload and status timeouts |
| `SubmissionProgressResponse` | The client owns submission polling |
| `SubmitFileBodyPublisher.buildMultipartData` | The existing streaming `getBodyPublisher` |

`PlateSolveResult` metadata is an immutable copy; a null map becomes empty, null keys are ignored, and null values are retained. Actual backend results include `source`; caller-constructed results need not. Absent or blank failure/warning text is null. Callers must omit absent warnings when presenting failures; SpacePixels does this. These are intentional result-contract changes alongside the operation API migration.

### Offline contract tests

`test` uses a portable Java child process and a loopback HTTP server. It requires no ASTAP installation, account, external service or uploads of observer data. `AstrometryDotNetConfig.Builder.withServiceBaseUri(URI)` selects a private server or loopback fixture; result links use the same root. Live `integrationTest` remains separate and opt-in.

The offline suite checks the actual blind multipart JSON, private defaults, explicit public opt-in, immutable configuration, credential-free diagnostics, independent HTTP phase limits, total deadlines, slow streamed uploads and cancellation with synthetic credentials. It also verifies the live-test FITS-copy helper on multiple HDUs and both real fixtures, preserving image samples without changing the originals.

Live tests upload temporary copies with neutral names. Every FITS header has observing-site and observer cards removed; checksum cards are also removed from the rewritten copies. All uploads are private. Tests use the operation API, require M101 and M5 solutions near their known centres, and distinguish an unsolvable fixture (`REJECTED`) from transport failure or timeout. Set the optional `ASTROMETRY_API_KEY` environment variable to use your own account; leaving it absent uses anonymous login. Tests report account mode without credentials and leave global logging untouched. Set `ASTAP_EXECUTABLE` to enable the two real local blind-solve checks; they verify WCS coordinates, source hashes, a pre-existing annotated JPEG and contradictory FITS hints. Local checks are skipped if that executable is not configured. Back up and restore the installed GUI solver's settings around real tests; Java `user.home` does not isolate ASTAP's native settings.

## 🛠️ Building JPlateSolve from Source

JPlateSolve uses [Gradle](https://gradle.org/) as its build system. To build the standalone `.jar` library yourself:

1. Clone the repository:
   ```bash
   git clone [https://github.com/ppissias/jplatesolve.git](https://github.com/ppissias/jplatesolve.git)
   cd jplatesolve
   ```

2. Build the project using the Gradle Wrapper:
   ```bash
   # On Linux/macOS
   ./gradlew build
   
   # On Windows
   gradlew.bat build
   ```

3. The compiled `.jar` file will be located in the `build/libs/` directory.

4. Live Astrometry.net checks are separated from the default test task:
   ```bash
   ./gradlew integrationTest
   ```

## 📦 Publishing to Maven Central

JPlateSolve is configured to generate a Maven Central-compatible publication with:

* `javadoc.jar`
* `sources.jar`
* POM metadata
* PGP signatures
* checksum files

Because the Central Portal does not currently provide an official Gradle publishing plugin, this build produces a signed upload bundle you can submit to the Portal manually.

1. Create `gradle.properties` in the project root or `%USERPROFILE%\.gradle\gradle.properties`, then fill in your local values.

2. Configure signing credentials. The simplest option is in-memory Gradle properties:
   ```bash
   export ORG_GRADLE_PROJECT_signingKey="..."
   export ORG_GRADLE_PROJECT_signingPassword="..."
   ```

3. Build the signed Central bundle:
   ```bash
   ./gradlew clean mavenCentralBundle
   ```

4. Upload the generated file from:
   ```text
   build/distributions/jplatesolve-<version>-central-bundle.zip
   ```

Before uploading, make sure your `io.github.ppissias` namespace is verified in the Central Portal.

The full standardized release workflow is documented in [PUBLISHING.md](PUBLISHING.md).

## 📄 License

BSD 2-Clause License
