/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jplatesolve.astap;

import io.github.ppissias.jplatesolve.PlateSolveResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads ASTAP output files for a previously started solve.
 * <p>
 * The reader waits for ASTAP's generated {@code .ini} file, parses its
 * key/value pairs, and converts them into a {@link PlateSolveResult}. On a
 * successful solve the returned metadata also includes the expected annotated
 * image path and WCS file path.
 */
public class ASTAPSolveResultsReader {

    static final Duration DEFAULT_RESULT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration FILE_POLL_INTERVAL = Duration.ofMillis(250);

    private final String fileBeingSolvedFullPath;
    private final Duration resultTimeout;

    private final Logger logger = Logger.getLogger(ASTAPSolveResultsReader.class.getName());

    /**
     * Returns the image path this reader expects ASTAP to solve.
     *
     * @return full path of the source image
     */
    public String getFileBeingSolved() {
        return fileBeingSolvedFullPath;
    }

    /**
     * Creates a reader using the default timeout for ASTAP result files.
     *
     * @param fileBeingSolvedFullPath full path of the source image
     */
    public ASTAPSolveResultsReader(String fileBeingSolvedFullPath) {
        this(fileBeingSolvedFullPath, DEFAULT_RESULT_TIMEOUT);
    }

    /**
     * Creates a reader for ASTAP outputs associated with the provided image.
     *
     * @param fileBeingSolvedFullPath full path of the source image
     * @param resultTimeout maximum time to wait for the generated {@code .ini}
     *        file; non-positive values fall back to the default timeout
     */
    public ASTAPSolveResultsReader(String fileBeingSolvedFullPath, Duration resultTimeout) {
        super();
        this.fileBeingSolvedFullPath = fileBeingSolvedFullPath;
        this.resultTimeout = sanitizeTimeout(resultTimeout);
    }

    /**
     * Waits for ASTAP's result file, parses it, and returns the corresponding
     * plate-solve result.
     *
     * @return parsed solve result populated from the ASTAP {@code .ini} file
     * @throws IOException if the result file cannot be found in time, cannot be
     *         read, or contains unexpected content
     */
    public PlateSolveResult getSolveResult() throws IOException {

        //determine .ini filename
        String iniFileName = getExpectedIniFilename(fileBeingSolvedFullPath);

        //wait for file to be present
        File iniFile = new File(iniFileName);
        long deadlineNanos = System.nanoTime() + resultTimeout.toNanos();
        while (!iniFile.exists()) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for ASTAP results file: " + iniFileName);
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new IOException("Timed out waiting for ASTAP results file after "
                        + resultTimeout.toMillis() + " ms: " + iniFileName);
            }
            try {
                logger.fine("Waiting for file to become available:" + iniFileName);
                long remainingMillis = Math.max(1L, Duration.ofNanos(Math.max(0L, deadlineNanos - System.nanoTime())).toMillis());
                Thread.sleep(Math.min(FILE_POLL_INTERVAL.toMillis(), remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for ASTAP results file: " + iniFileName, e);
            }
        }

        //solve result (including all properties)
        Map<String, String> solveResult = new HashMap<>();
        
        //read and parse .ini file natively
        List<String> lines = Files.readAllLines(iniFile.toPath(), StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line == null || line.trim().isEmpty() || line.trim().startsWith(";") || line.trim().startsWith("#") || line.trim().startsWith("[")) {
                continue;
            }
            int separatorIndex = line.indexOf('=');
            if (separatorIndex != -1) {
                String key = line.substring(0, separatorIndex).trim();
                String value = line.substring(separatorIndex + 1).trim();
                solveResult.put(key, value);
            }
        }

        //basic results
        String PLTSOLVD = solveResult.get("PLTSOLVD");
        String WARNING = solveResult.get("WARNING");

        solveResult.put("source", "astap");
        //return results
        if ("T".equals(PLTSOLVD)) {
            solveResult.put("annotated_image_link", getExpectedAnnotatedFilename(fileBeingSolvedFullPath));
            solveResult.put("wcs_link", getExpectedWCSFilename(fileBeingSolvedFullPath));

            return new PlateSolveResult(true, null, WARNING, solveResult);
        } else if ("F".equals(PLTSOLVD)) {
            String ERROR = solveResult.get("ERROR");
            return new PlateSolveResult(false, ERROR, WARNING, solveResult);
        } else {
            throw new IOException("Unexpected value at ini file PLTSOLVD=" + PLTSOLVD);
        }
    }

    /**
     * Expected .ini file
     *
     * @param fitsFileName
     * @return
     */
    private String getExpectedIniFilename(String fitsFileName) {
        int lastSepPosition = fitsFileName.lastIndexOf(".");
        return fitsFileName.substring(0, lastSepPosition) + ".ini";

    }

    /**
     * Expected .jpg file
     *
     * @param fitsFileName
     * @return
     */
    private String getExpectedAnnotatedFilename(String fitsFileName) {
        int lastSepPosition = fitsFileName.lastIndexOf(".");
        return fitsFileName.substring(0, lastSepPosition) + "_annotated.jpg";
    }

    /**
     * Expected .wcs file
     *
     * @param fitsFileName
     * @return
     */
    private String getExpectedWCSFilename(String fitsFileName) {
        int lastSepPosition = fitsFileName.lastIndexOf(".");
        return fitsFileName.substring(0, lastSepPosition) + ".wcs";
    }

    private static Duration sanitizeTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return DEFAULT_RESULT_TIMEOUT;
        }
        return timeout;
    }
}
