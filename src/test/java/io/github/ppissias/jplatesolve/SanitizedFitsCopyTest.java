package io.github.ppissias.jplatesolve;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** Verifies upload-fixture sanitization without an external service or modifying original images. */
public class SanitizedFitsCopyTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Removes private cards from every HDU while preserving pixels, pointing hints and the source bytes. */
    @Test
    public void sanitizesAllHeadersWithoutChangingTheOriginal() throws Exception {
        File source = folder.newFile("source.fits");
        short[][] pixels = {{1, 2}, {3, 4}};
        try (Fits fits = new Fits()) {
            for (int index = 0; index < 2; index++) {
                BasicHDU<?> hdu = Fits.makeHDU(pixels);
                for (String keyword : SanitizedFitsCopy.PRIVATE_KEYWORDS) {
                    hdu.getHeader().addValue(keyword, "synthetic-private-value", null);
                }
                hdu.getHeader().addValue("SITELAT", 12.5, null);
                hdu.getHeader().addValue("SITELONG", -34.5, null);
                hdu.getHeader().addValue("OBSERVER", "synthetic-observer", null);
                hdu.getHeader().addValue("OBJCTRA", "12 30 00", null);
                hdu.getHeader().addValue("OBJCTDEC", "+41 15 00", null);
                hdu.getHeader().addValue("CHECKSUM", "synthetic", null);
                hdu.getHeader().addValue("DATASUM", "0", null);
                fits.addHDU(hdu);
            }
            fits.write(source);
        }
        File original = new File(folder.getRoot(), "original.fits");
        Files.copy(source.toPath(), original.toPath());
        File copy = SanitizedFitsCopy.create(source, folder.getRoot());
        assertNotEquals(source, copy);
        assertEquals(-1L, Files.mismatch(original.toPath(), source.toPath()));
        try (Fits fits = new Fits(copy)) {
            BasicHDU<?>[] hdus = fits.read();
            assertEquals(2, hdus.length);
            for (BasicHDU<?> hdu : hdus) {
                assertFalse(hdu.getHeader().containsKey("SITELAT"));
                assertFalse(hdu.getHeader().containsKey("SITELONG"));
                assertFalse(hdu.getHeader().containsKey("OBSERVER"));
                for (String keyword : SanitizedFitsCopy.PRIVATE_KEYWORDS) {
                    assertFalse(keyword, hdu.getHeader().containsKey(keyword));
                }
                assertFalse(hdu.getHeader().containsKey("CHECKSUM"));
                assertFalse(hdu.getHeader().containsKey("DATASUM"));
                assertEquals("12 30 00", hdu.getHeader().getStringValue("OBJCTRA"));
                assertEquals("+41 15 00", hdu.getHeader().getStringValue("OBJCTDEC"));
                short[][] copiedPixels = (short[][]) hdu.getKernel();
                assertArrayEquals(pixels[0], copiedPixels[0]);
                assertArrayEquals(pixels[1], copiedPixels[1]);
            }
        }
    }

    /** The actual live FITS fixtures retain every image sample and pointing hint after sanitization. */
    @Test
    public void liveFixturesKeepTheirPixelsAndPointingHints() throws Exception {
        for (String name : new String[]{"L_2020-06-06_22-05-32_Bin1x1_120s__10C.fit",
                "L_2020-06-07_04-26-07_Bin1x1_120s__10C.fit"}) {
            File source = new File("src/test/resources", name);
            File original = new File(folder.newFolder(), name);
            Files.copy(source.toPath(), original.toPath());
            File copy = SanitizedFitsCopy.create(source, folder.getRoot());
            assertEquals(-1L, Files.mismatch(original.toPath(), source.toPath()));
            try (Fits input = new Fits(source); Fits output = new Fits(copy)) {
                BasicHDU<?>[] sourceHdus = input.read();
                BasicHDU<?>[] copiedHdus = output.read();
                assertEquals(sourceHdus.length, copiedHdus.length);
                for (int index = 0; index < sourceHdus.length; index++) {
                    for (String keyword : SanitizedFitsCopy.PRIVATE_KEYWORDS) {
                        assertFalse(keyword, copiedHdus[index].getHeader().containsKey(keyword));
                    }
                    for (String keyword : new String[]{"OBJCTRA", "OBJCTDEC"}) {
                        assertEquals(sourceHdus[index].getHeader().getStringValue(keyword),
                                copiedHdus[index].getHeader().getStringValue(keyword));
                    }
                    assertArrayEquals((Object[]) sourceHdus[index].getKernel(), (Object[]) copiedHdus[index].getKernel());
                }
            }
        }
    }
}
