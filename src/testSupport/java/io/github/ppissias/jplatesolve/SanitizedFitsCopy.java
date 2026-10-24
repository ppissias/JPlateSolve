package io.github.ppissias.jplatesolve;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

/** Builds temporary FITS fixtures without observing-site or observer metadata for live uploads. */
final class SanitizedFitsCopy {
    static final Set<String> PRIVATE_KEYWORDS = Set.of(
            "SITELAT", "SITELONG", "SITELON", "SITEELEV", "SITENAME",
            "OBSGEO-B", "OBSGEO-L", "OBSGEO-H", "OBSGEO-X", "OBSGEO-Y", "OBSGEO-Z",
            "LAT-OBS", "LONG-OBS", "LON-OBS", "ALT-OBS", "ELEV-OBS", "OBSALT", "ELEVATIO",
            "OBSLAT", "OBSLONG", "OBSLON", "OBSELEV", "LATITUDE", "LONGITUD",
            "GEOLAT", "GEOLON", "GEOELEV", "OBSERVAT", "OBSERVER");

    /** Prevents construction of the fixture utility. */
    private SanitizedFitsCopy() {
    }

    /** Copies every HDU into a neutral temporary file; the caller owns its directory and cleanup. */
    static File create(File source, File directory) throws IOException, FitsException {
        File copy = Files.createTempFile(directory.toPath(), "solve-upload-", ".fits").toFile();
        try (Fits input = new Fits(source); Fits output = new Fits()) {
            for (BasicHDU<?> hdu : input.read()) {
                for (String keyword : PRIVATE_KEYWORDS) hdu.getHeader().deleteKey(keyword);
                hdu.getHeader().deleteKey("CHECKSUM");
                hdu.getHeader().deleteKey("DATASUM");
                output.addHDU(hdu);
            }
            output.write(copy);
        }
        return copy;
    }
}
