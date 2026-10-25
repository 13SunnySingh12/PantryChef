package com.pantrychef;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Registers a fresh user over the real API and returns a bearer token for it. */
public final class TestUsers {

	private static final AtomicInteger CLIENT = new AtomicInteger();

	private TestUsers() {
	}

	public static String signUp(MockMvc mvc) throws Exception {
		String body = "{\"email\":\"user-" + UUID.randomUUID() + "@example.com\",\"password\":\"test password 123\"}";
		int n = CLIENT.incrementAndGet();
		mvc.perform(post("/api/v1/auth/register").with((r) -> {
			// distinct client address per user so the registration rate limit never trips in tests
			r.setRemoteAddr("10.200." + (n / 256 % 256) + "." + (n % 256));
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(body));
		String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return "Bearer " + JsonPath.read(login, "$.accessToken");
	}

}
