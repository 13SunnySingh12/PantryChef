package com.pantrychef.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies before anything parses them (ROAD-024): over {@link #MAX_BYTES} → 413, and a body with no
 * declared length (chunked) → 411, so the cap can't be bypassed. The largest real payload is about 10 KB.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeFilter extends OncePerRequestFilter {

	public static final long MAX_BYTES = 64 * 1024;

	private final JsonMapper json;

	public RequestSizeFilter(JsonMapper json) {
		this.json = json;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long length = request.getContentLengthLong();
		if (length > MAX_BYTES) {
			reject(response, 413, "PAYLOAD_TOO_LARGE", "The request is too large.");
		}
		else if (length < 0 && request.getHeader("Transfer-Encoding") != null) {
			reject(response, 411, "LENGTH_REQUIRED", "The request must declare its length.");
		}
		else {
			chain.doFilter(request, response);
		}
	}

	private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		this.json.writeValue(response.getOutputStream(), ApiErrors.body(code, message, null));
	}

}
