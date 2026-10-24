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

import java.io.File;
import java.util.concurrent.TimeUnit;

import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import io.github.ppissias.jplatesolve.astrometrydotnet.SubmitFileRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Opt-in private custom solve of a sanitized M5 fixture; consumes processing time at Astrometry.net.
 */
public class TestCustomSolve {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Solves a sanitized copy privately and checks the known M5 field centre. */
    @Test
    public void testFileSubmitRequest() throws Exception {
        File copy = SanitizedFitsCopy.create(
                new File("src/test/resources/L_2020-06-06_22-05-32_Bin1x1_120s__10C.fit"), folder.getRoot());
        SubmitFileRequest parameters = SubmitFileRequest.builder().withPublicly_visible("n").withScale_units("degwidth")
                .withScale_lower(0.1f).withScale_upper(180.0f).withDownsample_factor(2f).withRadius(1.0f).build();
        try (AstrometryDotNet client = LiveAstrometry.client()) {
            var outcome = client.startCustomSolve(copy, parameters, null).completion().toCompletableFuture().get(16, TimeUnit.MINUTES);
            LiveAstrometry.assertSolvedNear(outcome, 229.65, 2.08);
        }
    }
}
