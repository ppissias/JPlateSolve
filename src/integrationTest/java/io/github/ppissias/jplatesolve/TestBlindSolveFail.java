/*
 * SpacePixels
 * 
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jplatesolve;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.util.concurrent.TimeUnit;

import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Opt-in private solve of an unsolvable sanitized fixture; consumes processing time at Astrometry.net.
 */
public class TestBlindSolveFail {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Requires a solver rejection rather than treating a transport failure or timeout as an unsolved image. */
    @Test
    public void testFileSubmitRequest() throws Exception {
        File copy = SanitizedFitsCopy.create(
                new File("src/test/resources/L_2020-06-07_04-26-07_Bin1x1_120s__10C.fit"), folder.getRoot());
        try (AstrometryDotNet client = LiveAstrometry.client()) {
            var outcome = client.startBlindSolve(copy, null).completion().toCompletableFuture().get(16, TimeUnit.MINUTES);
            assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.REJECTED, outcome.status());
            assertNotNull(outcome.result());
            assertFalse(outcome.result().isSuccess());
        }
    }
}
