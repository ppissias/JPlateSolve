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
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Opt-in private blind solve of M101; consumes processing time at Astrometry.net.
 */
public class TestBlindSolve {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Solves a temporary JPEG copy privately and checks the known M101 field centre. */
    @Test
    public void testFileSubmitRequest() throws Exception {
        File copy = new File(folder.getRoot(), "solve-upload.jpg");
        Files.copy(new File("src/test/resources/m101.jpg").toPath(), copy.toPath());
        try (AstrometryDotNet client = LiveAstrometry.client()) {
            var outcome = client.startBlindSolve(copy, null).completion().toCompletableFuture().get(16, TimeUnit.MINUTES);
            LiveAstrometry.assertSolvedNear(outcome, 210.94, 54.20);
        }
    }
}
