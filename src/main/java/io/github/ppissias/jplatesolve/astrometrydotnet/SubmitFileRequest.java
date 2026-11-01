package io.github.ppissias.jplatesolve.astrometrydotnet;

/** Wire parameters for a blind upload; no position, scale or source-error constraints are sent. */
record SubmitFileRequest(String session, String publicly_visible) {
    /** Keeps the authentication token out of diagnostic text without changing JSON serialization. */
    @Override
    public String toString() {
        return "SubmitFileRequest [session=<redacted>, publicly_visible=" + publicly_visible + "]";
    }
}
