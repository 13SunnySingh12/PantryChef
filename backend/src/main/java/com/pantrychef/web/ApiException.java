package com.pantrychef.web;

import org.springframework.http.HttpStatus;

/** An expected failure with a stable code and a message that is safe to show to the user. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message, null, false, false);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

}
