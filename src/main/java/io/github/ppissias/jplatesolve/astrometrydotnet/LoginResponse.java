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
	@Override
	public String toString() {
		return "LoginResponse [status=" + status + ", message=" + message + ", session=" + session + "]";
	}
	
}
