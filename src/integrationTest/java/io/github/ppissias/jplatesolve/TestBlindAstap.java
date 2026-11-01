package io.github.ppissias.jplatesolve;

import io.github.ppissias.jplatesolve.astap.ASTAPInterface;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Opt-in real ASTAP checks on scratch copies; ASTAP_EXECUTABLE selects the installed solver. */
public class TestBlindAstap {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** A JPEG with no FITS hints is solved without generating or deleting an annotated sidecar. */
    @Test
    public void blindJpegPreservesTheSourceAndAnExistingJpeg() throws Exception {
        File image = new File(folder.getRoot(), "solve-input.jpg");
        Files.copy(new File("src/test/resources/m101.jpg").toPath(), image.toPath());
        File annotation = new File(folder.getRoot(), "solve-input_annotated.jpg");
        Files.writeString(annotation.toPath(), "pre-existing-user-file");
        solve(image, 210.94, 54.20);
        assertEquals("pre-existing-user-file", Files.readString(annotation.toPath()));
    }

    /** Misleading coordinate and optical cards cannot restrict the whole-sky, automatic-scale solve. */
    @Test
    public void blindFitsIgnoresContradictoryHeaderHints() throws Exception {
        File image = SanitizedFitsCopy.create(
                new File("src/test/resources/L_2020-06-06_22-05-32_Bin1x1_120s__10C.fit"), folder.getRoot());
        BasicHDU<?>[] hdus;
        try (Fits input = new Fits(image)) {
            hdus = input.read();
            for (BasicHDU<?> hdu : hdus) hdu.getKernel();
            var header = hdus[0].getHeader();
            header.addValue("OBJCTRA", "03 00 00", null);
            header.addValue("OBJCTDEC", "-45 00 00", null);
            header.addValue("RA", 45.0, null);
            header.addValue("DEC", -45.0, null);
            header.addValue("FOCALLEN", 100000.0, null);
            // Change the optical scale while preserving square-pixel geometry.
            header.addValue("XPIXSZ", 0.001, null);
            header.addValue("YPIXSZ", 0.001, null);
        }
        try (Fits output = new Fits()) {
            for (BasicHDU<?> hdu : hdus) output.addHDU(hdu);
            output.write(image);
        }
        solve(image, 229.65, 2.08);
    }

    /** Requires a real WCS near the known field and byte-identical input after owned cleanup. */
    private void solve(File image, double ra, double dec) throws Exception {
        String executable = System.getenv("ASTAP_EXECUTABLE");
        Assume.assumeTrue("Set ASTAP_EXECUTABLE for local live checks", executable != null && new File(executable).isFile());
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image.toPath()));
        long start = System.nanoTime();
        var operation = ASTAPInterface.startSolve(new File(executable), image.getAbsolutePath(), Duration.ofMinutes(5),
                progress -> System.out.println("ASTAP " + progress.phase() + " at " + progress.elapsed().toSeconds() + " s"));
        var outcome = operation.completion().toCompletableFuture().get(6, TimeUnit.MINUTES);
        System.out.println("ASTAP outcome " + outcome.status() + ", elapsed seconds " + (System.nanoTime() - start) / 1e9);
        assertEquals(String.valueOf(outcome.cause()), PlateSolveOperation.Status.SUCCEEDED, outcome.status());
        var metadata = outcome.result().getSolveInformation();
        assertEquals(ra, Double.parseDouble(metadata.get("CRVAL1")), 0.5);
        assertEquals(dec, Double.parseDouble(metadata.get("CRVAL2")), 0.5);
        assertFalse(metadata.containsKey("annotated_image_link"));
        try (Fits wcs = new Fits(new File(metadata.get("wcs_link")))) {
            var header = wcs.getHDU(0).getHeader();
            assertEquals(ra, header.getDoubleValue("CRVAL1"), 0.5);
            assertEquals(dec, header.getDoubleValue("CRVAL2"), 0.5);
        }
        assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image.toPath())));
        System.out.println("ASTAP solved centre degrees " + metadata.get("CRVAL1") + ", " + metadata.get("CRVAL2"));
    }
}
