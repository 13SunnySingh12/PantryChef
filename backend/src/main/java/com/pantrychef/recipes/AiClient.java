package com.pantrychef.recipes;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

import com.pantrychef.web.ApiException;
import com.pantrychef.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Server-to-server calls to the FastAPI AI service (D-009): shared-secret header, request ID propagation, bounded
 * timeouts. Failures become safe public errors; AI-service details never reach the browser.
 */
@Component
public class AiClient {

	private static final Logger log = LoggerFactory.getLogger(AiClient.class);

	private final RestClient http;

	private final String token;

	public AiClient(@Value("${pantrychef.ai.base-url}") String baseUrl, @Value("${pantrychef.ai.service-token}") String token,
			@Value("${pantrychef.ai.connect-timeout}") Duration connectTimeout,
			@Value("${pantrychef.ai.read-timeout}") Duration readTimeout) {
		// HTTP/1.1 explicitly: the JDK default tries an h2c upgrade, which uvicorn does not answer.
		var factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build());
		factory.setReadTimeout(readTimeout);
		this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
		this.token = token;
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> post(String path, Object body) {
		if (this.token == null || this.token.length() < 32) {
			throw unavailable();
		}
		try {
			return this.http.post()
				.uri(path)
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Service-Token", this.token)
				.header(RequestIdFilter.HEADER, MDC.get(RequestIdFilter.MDC_KEY))
				.body(body)
				.retrieve()
				.body(Map.class);
		}
		catch (ResourceAccessException ex) {
			if (ex.getCause() instanceof HttpTimeoutException) {
				log.warn("AI service timed out on {}", path);
				throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
						"Recipe suggestions took too long. Try again.");
			}
			log.warn("AI service unreachable on {}: {}", path, ex.getClass().getSimpleName());
			throw unavailable();
		}
		catch (RestClientResponseException ex) {
			log.warn("AI service answered {} on {}", ex.getStatusCode().value(), path);
			if (ex.getStatusCode().value() == 503) {
				throw unavailable();
			}
			throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR", "Recipe suggestions failed. Try again later.");
		}
	}

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
				"Recipe suggestions are temporarily unavailable.");
	}

}
