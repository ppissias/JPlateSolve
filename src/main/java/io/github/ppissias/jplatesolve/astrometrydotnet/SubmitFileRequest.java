package io.github.ppissias.jplatesolve.astrometrydotnet;

/** Wire parameters; absent hints omit every position/scale constraint from blind uploads. */
record SubmitFileRequest(String session, String publicly_visible, Double center_ra, Double center_dec,
                         Double radius, String scale_units, String scale_type, Double scale_lower, Double scale_upper) {
    /** Preserves the blind-upload representation and redacted diagnostics. */
    SubmitFileRequest(String session, String publicly_visible) {
        this(session, publicly_visible, null, null, null, null, null, null, null);
    }

    /** Copies optional immutable hints; a factor-two scale interval allows registration resampling. */
    static SubmitFileRequest create(String session, String visibility, io.github.ppissias.jplatesolve.SolveHints hints) {
        return hints == null ? new SubmitFileRequest(session, visibility)
                : new SubmitFileRequest(session, visibility, hints.raDegrees(), hints.decDegrees(), hints.radiusDegrees(),
                        "arcsecperpix", "ul", hints.arcsecPerPixel() / 2, hints.arcsecPerPixel() * 2);
    }
    /** Keeps the authentication token out of diagnostic text without changing JSON serialization. */
    @Override
    public String toString() {
        return "SubmitFileRequest [session=<redacted>, publicly_visible=" + publicly_visible + "]";
    }
}
