package io.github.ppissias.jplatesolve.astrometrydotnet;

/*
 * SpacePixels
 * 
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
/**
 * Response returned by Astrometry.net's login endpoint.
 * <p>
 * A successful response provides the session identifier used by subsequent
 * upload requests.
 */
public class LoginResponse {
	private String status;
	private String message;
	private String session;
	/** Why a login was refused, e.g. {@code bad apikey}; absent on success. */
	private String errormessage;

	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
	public String getMessage() {
		return message;
	}
	public void setMessage(String message) {
		this.message = message;
	}
	public String getSession() {
		return session;
	}
	public void setSession(String session) {
		this.session = session;
	}
	public String getErrormessage() {
		return errormessage;
	}
	public void setErrormessage(String errormessage) {
		this.errormessage = errormessage;
	}
	/** Returns diagnostic fields with the authentication token redacted. */
	@Override
	public String toString() {
		return "LoginResponse [status=" + status + ", message=" + message + ", session=<redacted>"
				+ ", errormessage=" + errormessage + "]";
	}
	
}
