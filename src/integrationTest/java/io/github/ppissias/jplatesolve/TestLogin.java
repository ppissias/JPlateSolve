/*
 * SpacePixels
 * 
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jplatesolve;

import static org.junit.Assert.assertNotNull;

import java.io.IOException;

import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import org.junit.Test;


/** Verifies an opt-in live login without printing credentials or changing global logging. */
public class TestLogin {

	/** Checks that authentication produces a session while reporting only the account mode. */
	@Test public void testLogin() throws IOException, InterruptedException {
		try (AstrometryDotNet astrometryLib = LiveAstrometry.client()) {
			astrometryLib.login();
			assertNotNull("Received session id", astrometryLib.getSessionID());
		}
	}
}
