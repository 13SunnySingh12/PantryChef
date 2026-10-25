package com.pantrychef.web;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** An expected failure with a stable code and a message that is safe to show to the user. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final Map<String, String> fieldErrors;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, null);
	}

	private ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
		super(message, null, false, false);
		this.status = status;
		this.code = code;
		this.fieldErrors = fieldErrors;
	}

	/** A field-level validation failure that a DTO annotation cannot express (cross-field or byte-length rules). */
	public static ApiException invalidField(String field, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Check the highlighted fields and try again.",
				Map.of(field, message));
	}

	public static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
	}

	public HttpStatus status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

	public Map<String, String> fieldErrors() {
		return this.fieldErrors;
	}

}
