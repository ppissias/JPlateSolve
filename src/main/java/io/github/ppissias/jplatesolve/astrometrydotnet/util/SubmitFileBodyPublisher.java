/*
 * SpacePixels
 * 
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jplatesolve.astrometrydotnet.util;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;

/**
 * Utilities for building the multipart HTTP request body used by Astrometry.net
 * file uploads.
 */
public class SubmitFileBodyPublisher {

	/**
	 * Returns a streaming multipart body publisher for an Astrometry.net upload.
	 *
	 * @param file image file to upload
	 * @param submitFileJSON Astrometry.net request-json payload
	 * @param boundary multipart boundary
	 * @return multipart body publisher suitable for {@link java.net.http.HttpRequest}
	 * @throws IOException if the file cannot be accessed
	 */
	public static BodyPublisher getBodyPublisher(File file, String submitFileJSON, String boundary) throws IOException {
		String lineEnd = "\r\n";
		String jsonPart = "--" + boundary + lineEnd
				+ "Content-Disposition: form-data; name=\"request-json\"" + lineEnd + lineEnd
				+ submitFileJSON + lineEnd;
		String filePartHeader = "--" + boundary + lineEnd
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"" + lineEnd
				+ "Content-Type: application/octet-stream" + lineEnd + lineEnd;
		String closingBoundary = lineEnd + "--" + boundary + "--" + lineEnd;

		return BodyPublishers.concat(
				BodyPublishers.ofString(jsonPart, StandardCharsets.UTF_8),
				BodyPublishers.ofString(filePartHeader, StandardCharsets.UTF_8),
				BodyPublishers.ofFile(file.toPath()),
				BodyPublishers.ofString(closingBoundary, StandardCharsets.UTF_8));		
	}

}
