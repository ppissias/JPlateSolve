package io.github.ppissias.jplatesolve;

import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNetConfig;

import static org.junit.Assert.*;

/** Shares credential-safe client setup and semantic solution checks for opt-in live tests. */
final class LiveAstrometry {
    /** Prevents construction of the live-test utility. */
    private LiveAstrometry() {
    }

    /** Creates an owned client and reports only whether an optional environment key was supplied. */
    static AstrometryDotNet client() {
        String key = System.getenv("ASTROMETRY_API_KEY");
        System.out.println("Astrometry.net login: " + (key == null || key.isBlank() ? "anonymous" : "supplied key"));
        return new AstrometryDotNet(AstrometryDotNetConfig.builder().withApiKey(key).build());
    }

    /** Requires a successful solution within half a degree of the fixture's known equatorial centre. */
    static void assertSolvedNear(PlateSolveOperation.Outcome outcome, double rightAscension, double declination) {
        assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
        assertNotNull(outcome.result());
        assertTrue(outcome.result().isSuccess());
        double solvedRa = Double.parseDouble(outcome.result().getSolveInformation().get("ra"));
        double solvedDec = Double.parseDouble(outcome.result().getSolveInformation().get("dec"));
        assertEquals("Right ascension in degrees", rightAscension, solvedRa, 0.5);
        assertEquals("Declination in degrees", declination, solvedDec, 0.5);
        System.out.println("Solved centre (degrees): RA " + solvedRa + ", Dec " + solvedDec);
    }
}
