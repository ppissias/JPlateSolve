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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable result returned by a plate-solving backend.
 * <p>
 * A successful result contains backend-specific metadata in
 * {@link #getSolveInformation()}. A failed result keeps the backend response, if
 * available, in the same map so callers can log or surface additional details.
 * The map always includes a {@code source} entry identifying the solver that
 * produced the result.
 */
public class PlateSolveResult {

	private final boolean success;
	private final String failureReason;
	private final String warning;
	
	private final Map<String, String> solveInformation;

	/**
	 * Creates a new plate solve result.
	 *
	 * @param success whether the solve operation completed successfully
	 * @param failureReason human-readable failure description, or {@code null} when
	 *        the solve succeeded; blank text is normalized to {@code null}
	 * @param warning optional warning emitted by the solver, or {@code null} when
	 *        there is none; blank text is normalized to {@code null}
	 * @param solveInformation backend-specific metadata and output links; defensively copied
	 *        as an unmodifiable map (a {@code null} map becomes an empty map)
	 */
	public PlateSolveResult(boolean success, String failureReason, String warning,
			Map<String, String> solveInformation) {
		super();
		this.success = success;
		this.failureReason = (failureReason == null || failureReason.isBlank()) ? null : failureReason;
		this.warning = (warning == null || warning.isBlank()) ? null : warning;
		if (solveInformation == null || solveInformation.isEmpty()) {
			this.solveInformation = Collections.emptyMap();
		} else {
			Map<String, String> copy = new LinkedHashMap<>();
			for (Map.Entry<String, String> entry : solveInformation.entrySet()) {
				if (entry.getKey() != null) {
					copy.put(entry.getKey(), entry.getValue());
				}
			}
			this.solveInformation = Collections.unmodifiableMap(copy);
		}
	}

	/**
	 * Returns whether the solver produced a valid astrometric solution.
	 *
	 * @return {@code true} when the image was solved successfully
	 */
	public boolean isSuccess() {
		return success;
	}

	/**
	 * Returns the failure reason when {@link #isSuccess()} is {@code false}.
	 *
	 * @return backend-specific failure text, or {@code null} when the solve
	 *         succeeded
	 */
	public String getFailureReason() {
		return failureReason;
	}

	/**
	 * Returns an optional warning emitted by the backend.
	 *
	 * @return warning text, or {@code null} when no warning was provided
	 */
	public String getWarning() {
		return warning;
	}

	/**
	 * Returns backend-specific solve metadata.
	 * <p>
	 * Common entries include:
	 * {@code source},
	 * {@code annotated_image_link},
	 * {@code wcs_link},
	 * and backend-specific numeric fields such as RA, Dec, or pixel scale when
	 * they are available.
	 *
	 * @return solver metadata and output links
	 */
	public Map<String, String> getSolveInformation() {
		return solveInformation;
	}

	@Override
	public String toString() {
		return "PlateSolveResult [success=" + success + ", failureReason=" + failureReason + ", warning=" + warning
				+ ", solveInformation=" + solveInformation + "]";
	}
	
	
}
