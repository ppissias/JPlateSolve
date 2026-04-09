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
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Main ASTAP interface for plate solving.
 * Starts a background solve and returns a running future for the result.
 */
public class ASTAPInterface {

    private static final Logger LOGGER = Logger.getLogger(ASTAPInterface.class.getName());
    private static final AtomicInteger WORKER_COUNTER = new AtomicInteger();

    public static Future<PlateSolveResult> solveImage(File astapExecutable, String imageFullPath) {
        return solveImage(astapExecutable, imageFullPath, ASTAPSolveResultsReader.DEFAULT_RESULT_TIMEOUT);
    }

    public static Future<PlateSolveResult> solveImage(File astapExecutable, String imageFullPath, Duration resultTimeout) {
        FutureTask<PlateSolveResult> task = new FutureTask<>(() -> {

            //call ASTAP with correct arguments
            //do a simple test run of astap
            String[] cmdArray = new String[11];
            cmdArray[0] = astapExecutable.getAbsolutePath();
            cmdArray[1] = "-f";
            cmdArray[2] = imageFullPath;
            cmdArray[3] = "-r";
            cmdArray[4] = "360"; //blind if necessary
            cmdArray[5] = "-z";
            cmdArray[6] = "0";
            cmdArray[7] = "-fov";
            cmdArray[8] = "0";
            cmdArray[9] = "-wcs";
            cmdArray[10] = "-annotate";


            try {
                Process proc = Runtime.getRuntime().exec(cmdArray, null, astapExecutable.getParentFile());
                //wait 2 seconds
                Thread.sleep(2000);

                if (!proc.isAlive()) {
                    if (proc.exitValue() > 0) {
                        LOGGER.severe("ASTAP exited with error code:" + proc.exitValue());
                        throw new Exception("ASTAP exited with error code:" + proc.exitValue());
                    }
                }
                //proc.
            } catch (IOException e) {
                LOGGER.severe("Cannot execute ASTAP:" + e.getMessage());
                throw (e);
            }

            //wait for results and return to the user
            ASTAPSolveResultsReader astapSolveResultsReader = new ASTAPSolveResultsReader(imageFullPath, resultTimeout);
            PlateSolveResult ret = astapSolveResultsReader.getSolveResult();
            return ret;
        });

        Thread worker = new Thread(task, "jplatesolve-astap-" + WORKER_COUNTER.incrementAndGet());
        worker.setDaemon(true);
        worker.start();

        return task;
    }
}
