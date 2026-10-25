package com.pantrychef.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.pantrychef.PantryChefTest;
import com.pantrychef.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// FR-001, FR-002, FR-024, NFR-001/002/005; QA-001, QA-002, QA-003 (identity part), QA-021.
@PantryChefTest
@ExtendWith(OutputCaptureExtension.class)
class AuthTests {

	private static final String PASSWORD = "correct horse battery";

	private static final java.util.Random RANDOM = new java.util.Random();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient db;

	@Test
	void registerStoresOnlyAHashAndLoginIssuesAnExpiringToken() throws Exception {
		String email = email();
		register(email.toUpperCase(), PASSWORD).andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.password").doesNotExist());

		String hash = this.db.sql("SELECT password_hash FROM users WHERE email = ?").param(email).query(String.class).single();
		assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);

		String body = login(email, PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(3600))
			.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");
		String[] parts = token.split("\\.");
		String claims = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		long iat = ((Number) JsonPath.read(claims, "$.iat")).longValue();
		long exp = ((Number) JsonPath.read(claims, "$.exp")).longValue();
		assertThat(exp - iat).isEqualTo(3600);
		assertThat(claims).doesNotContain(email).doesNotContain(PASSWORD);

		this.mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email));
	}

	@Test
	void duplicateNormalizedEmailIsRejected() throws Exception {
		String email = email();
		register(email, PASSWORD).andExpect(status().isCreated());
		register("  " + email.toUpperCase() + " ", PASSWORD).andExpect(status().isBadRequest());
		register(email.toUpperCase(), PASSWORD).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EMAIL_UNAVAILABLE"));
		assertThat(this.db.sql("SELECT count(*) FROM users WHERE email = ?").param(email).query(Integer.class).single())
			.isEqualTo(1);
	}

	@Test
	void invalidRegistrationReturnsFieldErrorsWithoutWriting() throws Exception {
		register("not-an-email", "short").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty())
			.andExpect(jsonPath("$.error.fieldErrors.email").exists())
			.andExpect(jsonPath("$.error.fieldErrors.password").exists());
		// 37 two-byte characters: within the 72-character limit but 74 bytes, beyond what BCrypt reads
		register(email(), "é".repeat(37)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.fieldErrors.password").exists());
		this.mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		this.mvc.perform(post("/api/v1/auth/register").contentType(MediaType.TEXT_PLAIN).content("x"))
			.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void wrongPasswordAndUnknownEmailGetTheSameGenericError() throws Exception {
		String email = email();
		register(email, PASSWORD).andExpect(status().isCreated());
		String wrong = login(email, "wrong password!").andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		String unknown = login(email(), PASSWORD).andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(wrong, "$.error.code")).isEqualTo("INVALID_CREDENTIALS");
		assertThat((String) JsonPath.read(wrong, "$.error.message"))
			.isEqualTo(JsonPath.read(unknown, "$.error.message"));
	}

	@Test
	void repeatedFailedLoginsAreRateLimited() throws Exception {
		String email = email();
		register(email, PASSWORD).andExpect(status().isCreated());
		for (int i = 0; i < 5; i++) {
			login(email, "wrong password!").andExpect(status().isUnauthorized());
		}
		login(email, PASSWORD).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
	}

	@Test
	void registrationsFromOneClientAreRateLimited() throws Exception {
		for (int i = 0; i < 10; i++) {
			register(email(), PASSWORD, "10.9.9.9").andExpect(status().isCreated());
		}
		register(email(), PASSWORD, "10.9.9.9").andExpect(status().isTooManyRequests());
		register(email(), PASSWORD, "10.9.9.10").andExpect(status().isCreated());
	}

	@Test
	void privateRoutesRejectMissingMalformedForgedExpiredAndForeignTokens() throws Exception {
		me(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
		me("not-a-jwt").andExpect(status().isUnauthorized());

		String userId = register(email(), PASSWORD).andReturn().getResponse().getContentAsString();
		String subject = JsonPath.read(userId, "$.id");
		Instant now = Instant.now();
		me(sign("a-different-secret-that-is-also-32-bytes-long!", "pantrychef-local", subject, now.plusSeconds(600)))
			.andExpect(status().isUnauthorized());
		me(sign(TestcontainersConfiguration.TEST_JWT_SECRET, "pantrychef-local", subject, now.minusSeconds(600)))
			.andExpect(status().isUnauthorized());
		me(sign(TestcontainersConfiguration.TEST_JWT_SECRET, "someone-else", subject, now.plusSeconds(600)))
			.andExpect(status().isUnauthorized());
		me(sign(TestcontainersConfiguration.TEST_JWT_SECRET, "pantrychef-local", UUID.randomUUID().toString(),
				now.plusSeconds(600)))
			.andExpect(status().isUnauthorized());
		me(sign(TestcontainersConfiguration.TEST_JWT_SECRET, "pantrychef-local", subject, now.plusSeconds(600)))
			.andExpect(status().isOk());
	}

	@Test
	void eachTokenOnlyResolvesToItsOwnUser() throws Exception {
		String a = email();
		String b = email();
		register(a, PASSWORD);
		register(b, PASSWORD);
		me(token(a)).andExpect(jsonPath("$.email").value(a));
		me(token(b)).andExpect(jsonPath("$.email").value(b));
	}

	@Test
	void logoutIsIdempotentAndNeedsNoSession() throws Exception {
		this.mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
		this.mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
	}

	@Test
	void requestIdIsEchoedOrReplacedWhenUnsafe() throws Exception {
		this.mvc.perform(post("/api/v1/auth/logout").header("X-Request-Id", "abc12345-trace"))
			.andExpect(header().string("X-Request-Id", "abc12345-trace"));
		String generated = this.mvc.perform(post("/api/v1/auth/logout").header("X-Request-Id", "<script>"))
			.andReturn().getResponse().getHeader("X-Request-Id");
		assertThat(generated).isNotEqualTo("<script>").matches("[0-9a-f-]{36}");
	}

	@Test
	void logsNeverContainPasswordsOrTokens(CapturedOutput output) throws Exception {
		String email = email();
		register(email, PASSWORD);
		String token = token(email);
		login(email, "wrong password!");
		me(token);
		assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain("wrong password!").doesNotContain(token);
	}

	private ResultActions register(String email, String password) throws Exception {
		return register(email, password, "10.1." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256));
	}

	private ResultActions register(String email, String password, String clientAddress) throws Exception {
		return this.mvc.perform(post("/api/v1/auth/register").with((r) -> {
			r.setRemoteAddr(clientAddress);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private ResultActions login(String email, String password) throws Exception {
		return this.mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private ResultActions me(String token) throws Exception {
		var request = get("/api/v1/auth/me");
		return this.mvc.perform(token == null ? request : request.header("Authorization", "Bearer " + token));
	}

	private String token(String email) throws Exception {
		return JsonPath.read(login(email, PASSWORD).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}

	private static String sign(String secret, String issuer, String subject, Instant expiresAt) {
		var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.subject(subject)
			.issuedAt(expiresAt.minusSeconds(3600))
			.expiresAt(expiresAt)
			.build();
		return new NimbusJwtEncoder(new ImmutableSecret<>(key))
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	private static String email() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

}
