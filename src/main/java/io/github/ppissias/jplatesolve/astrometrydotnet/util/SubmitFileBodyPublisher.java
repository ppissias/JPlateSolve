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
 * Returns a BodyPublisher for the Submit File Request to 
 * Astrometry.net 
 * 
 * Thanks to https://github.com/ralscha/blog2019/blob/master/java11httpclient/client/src/main/java/ch/rasc/httpclient/File.java
 * for the example code
 *
 */
public class SubmitFileBodyPublisher {

	/**
	 * Suggestion from Gemini, in order to calculate the request header
	 * @param boundary
	 * @param json
	 * @param file
	 * @return
	 * @throws IOException
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
