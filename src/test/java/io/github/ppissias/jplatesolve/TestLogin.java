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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import org.junit.Test;


public class TestLogin {

	@Test public void testLogin() throws IOException, InterruptedException {	
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
		
		AstrometryDotNet astrometryLib = new AstrometryDotNet();
		astrometryLib.login(); 
		assertNotNull("Received session id", astrometryLib.getSessionID());
		System.out.println("Logged in with session id:"+astrometryLib.getSessionID());
	}
	
	
}
