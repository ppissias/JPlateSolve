/*
 * SpacePixels
 * 
 * Copyright (c)2020-2023, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jplatesolve;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Test;

import nom.tam.fits.FitsException;

/**
 * Please consider when running this test that it will consume CPU time at astrometry.net
 * Only run it if you want to specifically test some settings.
 *
 */
public class TestBlindSolveFail {

	@Test public void testFileSubmitRequest() throws IOException, InterruptedException, ExecutionException, FitsException {
		
		//just have logging for our own classes
		Logger logger = Logger.getLogger(AstrometryDotNet.class.getName());
		logger.setLevel(Level.FINEST);		
		for (Handler handler :logger.getHandlers()) {
			handler.setLevel(Level.FINEST);
		}
		logger.getParent().setLevel(Level.FINEST);
		for (Handler handler :logger.getParent().getHandlers()) {
			handler.setLevel(Level.FINEST);
		}
		Logger.getLogger("jdk").setLevel(Level.WARNING);
		Logger.getLogger("com").setLevel(Level.WARNING);
		
		//begin tests
		AstrometryDotNet astrometryLib = new AstrometryDotNet();
		astrometryLib.login(); 
		assertNotNull("Received session id", astrometryLib.getSessionID());
		System.out.println("Logged in with session id:"+astrometryLib.getSessionID());
		
		//test blind solve request with FITS file that will fail (tracking was off)
		File fileThatWillFail = new File("src/test/resources/L_2020-06-07_04-26-07_Bin1x1_120s__10C.fit");
		assertTrue("Test file exists",fileThatWillFail.exists());
		Future<PlateSolveResult> solveResult = astrometryLib.blindSolve(fileThatWillFail);
		System.out.println("Solve result: "+solveResult.get().toString());
		assertFalse("Solve result should be false",solveResult.get().isSuccess());		
	}
}
