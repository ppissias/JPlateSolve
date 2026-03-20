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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Helper class to read ASTAP plate solve results
 *
 */
public class ASTAPSolveResultsReader {

    private final String fileBeingSolvedFullPath;

    private final Logger logger = Logger.getLogger(ASTAPSolveResultsReader.class.getName());

    public String getFileBeingSolved() {
        return fileBeingSolvedFullPath;
    }

    public ASTAPSolveResultsReader(String fileBeingSolvedFullPath) {
        super();
        this.fileBeingSolvedFullPath = fileBeingSolvedFullPath;
    }

    public PlateSolveResult getSolveResult() throws IOException {

        //determine .ini filename
        String iniFileName = getExpectedIniFilename(fileBeingSolvedFullPath);

        //wait for file to be present
        File iniFile = new File(iniFileName);
        while (!iniFile.exists()) {
            try {
                logger.info("Waiting for file to become available:" + iniFileName);
                Thread.sleep(1000);
            } catch (InterruptedException e) {
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
}
