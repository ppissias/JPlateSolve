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
import java.nio.file.Files;
import java.io.ByteArrayOutputStream;

/**
 * Utilities for building the multipart HTTP request body used by Astrometry.net
 * file uploads.
 */
public class SubmitFileBodyPublisher {

	/**
	 * Builds the full multipart payload in memory.
	 * <p>
	 * This method is mainly useful for tests or diagnostics. Production upload
	 * requests should prefer {@link #getBodyPublisher(File, String, String)} to
	 * avoid buffering the whole file in memory.
	 *
	 * @param boundary multipart boundary
	 * @param json Astrometry.net request-json payload
	 * @param file image file to upload
	 * @return raw multipart payload bytes
	 * @throws IOException if the file cannot be read
	 */
	public static byte[] buildMultipartData(String boundary, String json, File file) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		String lineEnd = "\r\n";
		String twoHyphens = "--";

		// 1. Append the JSON part
		baos.write((twoHyphens + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
		baos.write(("Content-Disposition: form-data; name=\"request-json\"" + lineEnd + lineEnd).getBytes(StandardCharsets.UTF_8));
		baos.write((json + lineEnd).getBytes(StandardCharsets.UTF_8));

		// 2. Append the Image File part
		baos.write((twoHyphens + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
		baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"" + lineEnd).getBytes(StandardCharsets.UTF_8));
		baos.write(("Content-Type: application/octet-stream" + lineEnd + lineEnd).getBytes(StandardCharsets.UTF_8));
		baos.write(Files.readAllBytes(file.toPath()));
		baos.write((lineEnd).getBytes(StandardCharsets.UTF_8));

		// 3. Append the Closing Boundary
		baos.write((twoHyphens + boundary + twoHyphens + lineEnd).getBytes(StandardCharsets.UTF_8));

		return baos.toByteArray();
	}


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
