# 🌌 JPlateSolve

**JPlateSolve** is a pure Java library for automated astrometric plate solving. It provides a unified, asynchronous API to determine the exact celestial coordinates (Right Ascension and Declination) of astronomical images.

It serves as the plate-solving engine behind [SpacePixels](https://github.com/ppissias/SpacePixels) and is designed to be easily embedded into any Java-based astrophotography tool, observatory control software, or batch processing pipeline.

## ✨ Key Features

JPlateSolve supports two completely different backend engines, allowing you to choose between offline speed and online convenience:

1. **Local Plate Solving (ASTAP):** Interfaces directly with a local installation of the blazing-fast [ASTAP (Astrometric STAcking Program)](https://www.hnsky.org/astap.htm) executable. Perfect for rapid, offline solving.
2. **Cloud Plate Solving (Astrometry.net):** A fully featured Java wrapper for the [nova.astrometry.net](https://nova.astrometry.net/api_help) REST API. Supports both blind solving and custom solving with parameters.
3. **Smart FITS Parsing:** Automatically reads `nom.tam.fits` headers (like `OBJCTRA` and `OBJCTDEC`) to extract coordinate hints, dramatically speeding up the solving process.
4. **Asynchronous and Cancellable:** New solve operations expose phase progress and a completion stage that resolves after local cleanup. Legacy `Future<PlateSolveResult>` methods remain available.

## ⚠️ Prerequisites

* **Java 21 or higher:** 
* **For Local Solving:** You must have [ASTAP](https://www.hnsky.org/astap.htm) and its star databases (e.g., H18 or V17) installed on the host machine.
* **For Cloud Solving:** nothing to install. The no-argument client logs in anonymously with the built-in guest key (`XXXXXXXX`), which nova.astrometry.net accepts; that depends on the service continuing to accept it. To solve under your own account, pass your [Astrometry.net API key](https://nova.astrometry.net/api_help) with `new AstrometryDotNet(key)` or `AstrometryDotNetConfig.Builder.withApiKey`. Uploads made with your key show up on your Astrometry.net profile. A blank key falls back to the guest key.

### Upload privacy

Blind solves upload privately (`publicly_visible=n`). Custom solves also default to private when visibility is absent or blank, including through the legacy `Future` methods. To publish an upload deliberately, pass `SubmitFileRequest.builder().withPublicly_visible("y").build()` to a custom solve. A public upload shares the image and its FITS metadata on Astrometry.net.

Private visibility does not remove metadata from the file. The anonymous login uses a shared account whose other users can see its jobs. Remove observing-site and observer information from a temporary copy before uploading sensitive FITS images, as SpacePixels does. JPlateSolve uploads the file supplied by its caller; it does not sanitize or modify that original. Diagnostic `toString()` values redact session tokens while authentication payloads retain them.

## 🚀 Quick Start Guide

### 1. Add to your project
If you are using Gradle, include JPlateSolve in your dependencies:
```groovy
dependencies {
   implementation 'io.github.ppissias.jplatesolve:jplatesolve:1.0.1'
}
```

### 2. Example A: Local Solving via ASTAP
ASTAP solving is lightning fast but requires the executable to be present on the local machine.

```java
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astap.ASTAPInterface;

import java.io.File;
import java.util.concurrent.Future;

public class PlateSolverApp {
   public void solveLocally() throws Exception {
      File astapExe = new File("C:/Program Files/astap/astap.exe"); // Or /usr/bin/astap on Linux
      String targetImage = "C:/AstroData/light_frame.fits";

      System.out.println("Starting local solve...");

      // This starts ASTAP in the background and returns an already-running Future
      Future<PlateSolveResult> futureResult = ASTAPInterface.solveImage(astapExe, targetImage);

      // .get() will block until ASTAP finishes parsing the image
      PlateSolveResult result = futureResult.get();

      if (result.isSuccess()) {
         System.out.println("Solve Successful!");
         System.out.println("RA: " + result.getSolveInformation().get("ra"));
         System.out.println("DEC: " + result.getSolveInformation().get("dec"));
      } else {
         System.out.println("Solve failed: " + result.getFailureReason());
      }
   }
}
```

### 3. Example B: Cloud Solving via Astrometry.net
If the user doesn't have ASTAP, you can securely upload the image to the Astrometry.net cloud for blind solving.

```java
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;

import java.io.File;
import java.util.concurrent.Future;

public class CloudSolverApp {
   public void solveInCloud() throws Exception {
      File targetImage = new File("C:/AstroData/light_frame.fits");

      // Your own API key, or new AstrometryDotNet() for an anonymous login
      try (AstrometryDotNet cloudSolver = new AstrometryDotNet("your-api-key")) {

         System.out.println("Uploading image and awaiting blind solve...");

         // This fires off the upload and polling threads in the background
         Future<PlateSolveResult> futureResult = cloudSolver.blindSolve(targetImage);

         // .get() blocks until the remote job finishes (can take a few minutes)
         PlateSolveResult result = futureResult.get();

         if (result.isSuccess()) {
            System.out.println("Cloud Solve Successful!");
            System.out.println("Annotated Image URL: " + result.getSolveInformation().get("annotated_image_link"));
            System.out.println("Orientation: " + result.getSolveInformation().get("orientation"));
         } else {
            System.out.println("Cloud solve failed: " + result.getFailureReason());
         }
      }
   }
}
```

## Cancellation and completion in 1.0.1

For new integrations, use `ASTAPInterface.startSolve(executable, imagePath, budget, listener)`,
`AstrometryDotNet.startBlindSolve(image, listener)`, or
`AstrometryDotNet.startCustomSolve(image, parameters, listener)`.

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
- ASTAP completion follows confirmed process exit and output-reader shutdown. Both output streams are merged and drained with bounded retained diagnostics. Existing `.ini`, `.wcs` and annotated JPEG sidecars are backed up before launch; unsuccessful runs remove only their generated outputs and restore those backups. Successful runs replace the old sidecars. Same-stem concurrent solves in this JVM are rejected, including `frame.fit` versus `frame.fits`. Do not run another application against the same output stem concurrently.
- Cloud cancellation interrupts local HTTP work and stops polling. **It does not cancel a remote job already accepted by Astrometry.net.** No remote cancellation guarantee is made.
- The custom request is snapshotted, so login, FITS preparation and the default private visibility do not mutate the caller's object. Existing numeric-hint defaults are unchanged.
- Legacy methods remain source-compatible. Cloud preparation failures now arrive asynchronously through the returned future. A legacy future's cancelled `get()` may return before backend cleanup; retain the new operation handle when cleanup-aware cancellation is needed. `cancel(false)` does not interrupt a running worker.
- Close each cloud client when its session ends. `close()` cancels and joins its operations, shuts down HTTP resources, and joins an owned executor (shared 10-second limit, explicit failure if shutdown cannot be confirmed). Borrowed executors are never shut down; this client's queued operations are still cancelled. Starts after close are rejected.

### Offline contract tests

`test` uses a portable Java child process and a loopback HTTP server. It requires no ASTAP installation, account, external service or uploads of observer data. `AstrometryDotNetConfig.Builder.withServiceBaseUri(URI)` selects a private server or loopback fixture; result links use the same root. Live `integrationTest` remains separate and opt-in.

The offline suite checks private defaults, explicit public opt-in, request isolation and credential-free diagnostics with synthetic credentials. It also verifies the live-test FITS-copy helper on multiple HDUs and both real fixtures, preserving image samples and pointing hints without changing the originals.

Live tests upload temporary copies with neutral names. Every FITS header has observing-site and observer cards removed; checksum cards are also removed from the rewritten copies. All uploads are private. Tests use the operation API, require M101 and M5 solutions near their known centres, and distinguish an unsolvable fixture (`REJECTED`) from transport failure or timeout. Set the optional `ASTROMETRY_API_KEY` environment variable to use your own account; tests report only whether the login is anonymous or uses a supplied key, never the key or session token. They do not change global logging settings.

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
