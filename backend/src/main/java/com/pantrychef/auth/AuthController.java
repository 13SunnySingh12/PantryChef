package com.pantrychef.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.pantrychef.security.CurrentUser;
import com.pantrychef.security.RateLimiter;
import com.pantrychef.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts (FR-001, A-07): BCrypt hashes only, 60-minute access tokens, no refresh tokens. Logout is
 * client-side (the token is discarded). Failed logins are rate-limited per email.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	/** BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently truncated. */
	private static final int MAX_PASSWORD_BYTES = 72;

	private final JdbcClient db;

	private final PasswordEncoder passwords;

	private final JwtEncoder jwt;

	private final Clock clock;

	private final String issuer;

	private final Duration tokenTtl;

	private final RateLimiter failedLogins;

	private final RateLimiter registrations;

	/** Compared against when the email is unknown, so both failure paths cost one BCrypt check. */
	private final String dummyHash;

	public AuthController(JdbcClient db, PasswordEncoder passwords, JwtEncoder jwt, Clock clock,
			@Value("${pantrychef.jwt.issuer}") String issuer, @Value("${pantrychef.jwt.ttl}") Duration tokenTtl) {
		this.db = db;
		this.passwords = passwords;
		this.jwt = jwt;
		this.clock = clock;
		this.issuer = issuer;
		this.tokenTtl = tokenTtl;
		this.failedLogins = new RateLimiter(5, Duration.ofMinutes(15), clock);
		this.registrations = new RateLimiter(10, Duration.ofHours(1), clock);
		this.dummyHash = passwords.encode(UUID.randomUUID().toString());
	}

	public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotNull @Size(min = 8, max = 72) String password) {
	}

	public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {
	}

	public record UserResponse(UUID id, String email) {
	}

	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	@PostMapping("/register")
	ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
		String client = http.getRemoteAddr();
		if (this.registrations.isLimited(client)) {
			throw tooManyRequests();
		}
		if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw ApiException.invalidField("password", "must be at most 72 bytes");
		}
		this.registrations.record(client);
		String email = normalize(request.email());
		// Without an email channel (out of scope) registration cannot hide that an address is taken; the
		// per-client rate limit bounds how fast addresses can be probed.
		UUID id = this.db
			.sql("INSERT INTO users (email, password_hash) VALUES (?, ?) ON CONFLICT (email) DO NOTHING RETURNING id")
			.params(email, this.passwords.encode(request.password()))
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "EMAIL_UNAVAILABLE",
					"This email can't be used to register."));
		return ResponseEntity.status(HttpStatus.CREATED).body(new UserResponse(id, email));
	}

	@PostMapping("/login")
	TokenResponse login(@Valid @RequestBody LoginRequest request) {
		String email = normalize(request.email());
		if (this.failedLogins.isLimited(email)) {
			throw tooManyRequests();
		}
		var user = this.db.sql("SELECT id, password_hash FROM users WHERE email = ?")
			.param(email)
			.query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("password_hash")))
			.optional();
		boolean valid = this.passwords.matches(request.password(), user.map(Map.Entry::getValue).orElse(this.dummyHash));
		if (user.isEmpty() || !valid) {
			this.failedLogins.record(email);
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
		}
		this.failedLogins.reset(email);
		Instant now = this.clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(this.issuer)
			.subject(user.get().getKey().toString())
			.issuedAt(now)
			.expiresAt(now.plus(this.tokenTtl))
			.build();
		String token = this.jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
		return new TokenResponse(token, "Bearer", this.tokenTtl.toSeconds());
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout() {
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	UserResponse me(@AuthenticationPrincipal Jwt token) {
		UUID id = CurrentUser.id(token);
		String email = this.db.sql("SELECT email FROM users WHERE id = ?")
			.param(id)
			.query(String.class)
			.optional()
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."));
		return new UserResponse(id, email);
	}

	private static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private ApiException tooManyRequests() {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Try again later.");
	}

}
