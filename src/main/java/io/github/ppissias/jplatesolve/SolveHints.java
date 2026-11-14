package io.github.ppissias.jplatesolve;

/** Immutable approximate ICRS position and image scale; hints constrain a new solve, never constitute a WCS. */
public record SolveHints(double raDegrees, double decDegrees, double radiusDegrees,
                         double arcsecPerPixel, double imageHeightDegrees) {
    /** Validates finite coordinates and positive angular search/scale values, all in degrees except pixel scale. */
    public SolveHints {
        if (!Double.isFinite(raDegrees) || raDegrees < 0 || raDegrees >= 360
                || !Double.isFinite(decDegrees) || Math.abs(decDegrees) > 90
                || !Double.isFinite(radiusDegrees) || radiusDegrees <= 0 || radiusDegrees > 180
                || !Double.isFinite(arcsecPerPixel) || arcsecPerPixel <= 0
                || !Double.isFinite(imageHeightDegrees) || imageHeightDegrees <= 0 || imageHeightDegrees > 180) {
            throw new IllegalArgumentException("Invalid position or image-scale hint.");
        }
    }
}
