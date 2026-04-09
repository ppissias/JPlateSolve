# 🌌 JPlateSolve

**JPlateSolve** is a pure Java library for automated astrometric plate solving. It provides a unified, asynchronous API to determine the exact celestial coordinates (Right Ascension and Declination) of astronomical images.

It serves as the plate-solving engine behind [SpacePixels](https://github.com/ppissias/SpacePixels) and is designed to be easily embedded into any Java-based astrophotography tool, observatory control software, or batch processing pipeline.

## ✨ Key Features

JPlateSolve supports two completely different backend engines, allowing you to choose between offline speed and online convenience:

1. **Local Plate Solving (ASTAP):** Interfaces directly with a local installation of the blazing-fast [ASTAP (Astrometric STAcking Program)](https://www.hnsky.org/astap.htm) executable. Perfect for rapid, offline solving.
2. **Cloud Plate Solving (Astrometry.net):** A fully featured Java wrapper for the [nova.astrometry.net](https://nova.astrometry.net/api_help) REST API. Supports both blind solving and custom solving with parameters.
3. **Smart FITS Parsing:** Automatically reads `nom.tam.fits` headers (like `OBJCTRA` and `OBJCTDEC`) to extract focal length and coordinate hints, dramatically speeding up the solving process.
4. **Fully Asynchronous:** All solving methods return Java `Future<PlateSolveResult>` objects, ensuring your application's UI thread is never blocked while waiting for a heavy solve to complete.

## ⚠️ Prerequisites

* **Java 21 or higher:** 
* **For Local Solving:** You must have [ASTAP](https://www.hnsky.org/astap.htm) and its star databases (e.g., H18 or V17) installed on the host machine.
* **For Cloud Solving:** [nova.astrometry.net](https://nova.astrometry.net/) will be used 

## 🚀 Quick Start Guide

### 1. Add to your project
If you are using Gradle, include JPlateSolve in your dependencies:
```groovy
dependencies {
   implementation 'io.github.ppissias.jplatesolve:jplatesolve:1.0.0'
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

      try (AstrometryDotNet cloudSolver = new AstrometryDotNet()) {
         // Uses the built-in guest API key by default.
         // Pass your own key to new AstrometryDotNet("your-api-key") to override it.
         cloudSolver.login();

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
