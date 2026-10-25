package com.pantrychef.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to the spec's error envelope (§7.1). Validation failures are 400 (D-011). Client
 * responses never carry stack traces, SQL or exception messages.
 */
@RestControllerAdvice
public class ApiErrors {

	private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

	public record Body(Error error) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Error(String code, String message, String requestId, Map<String, String> fieldErrors) {
	}

	public static Body body(String code, String message, Map<String, String> fieldErrors) {
		return new Body(new Error(code, message, MDC.get(RequestIdFilter.MDC_KEY), fieldErrors));
	}

	static ResponseEntity<Body> respond(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(body(code, message, null));
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Body> api(ApiException ex) {
		return ResponseEntity.status(ex.status()).body(body(ex.code(), ex.getMessage(), ex.fieldErrors()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Body> invalid(MethodArgumentNotValidException ex) {
		Map<String, String> fields = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach((e) -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
		return ResponseEntity.badRequest()
			.body(body("VALIDATION_ERROR", "Check the highlighted fields and try again.", fields));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<Body> malformed() {
		return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request is missing data or is not valid.");
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<Body> notFound() {
		return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<Body> methodNotAllowed() {
		return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This action is not supported here.");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<Body> unsupportedMediaType() {
		return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send the request as JSON.");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Body> unexpected(Exception ex) {
		// Exception messages can contain SQL or user data, so only the type and origin are logged.
		StackTraceElement[] trace = ex.getStackTrace();
		log.error("Unhandled {} at {}", ex.getClass().getName(), trace.length > 0 ? trace[0] : "unknown");
		return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Try again later.");
	}

}
