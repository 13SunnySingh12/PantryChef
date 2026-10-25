package com.pantrychef.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.pantrychef.web.ApiErrors;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless bearer-token security (D-008): no cookies or server sessions, so CSRF protection is not needed.
 * Every route except register/login/logout requires a valid, unexpired HS256 token from this issuer.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
		AuthenticationEntryPoint unauthenticated = (req, res, ex) -> write(res, json, 401, "UNAUTHENTICATED",
				"Sign in to continue.");
		AccessDeniedHandler denied = (req, res, ex) -> write(res, json, 403, "FORBIDDEN", "You can't do that.");
		http.csrf((csrf) -> csrf.disable())
			.sessionManagement((s) -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests((auth) -> auth
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout")
				.permitAll()
				.requestMatchers("/error")
				.permitAll()
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer((rs) -> rs.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(unauthenticated)
				.accessDeniedHandler(denied))
			.exceptionHandling((e) -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
		return http.build();
	}

	private static void write(HttpServletResponse res, JsonMapper json, int status, String code, String message)
			throws IOException {
		res.setStatus(status);
		res.setContentType(MediaType.APPLICATION_JSON_VALUE);
		json.writeValue(res.getOutputStream(), ApiErrors.body(code, message, null));
	}

	@Bean
	SecretKey jwtSigningKey(@Value("${pantrychef.jwt.secret}") String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("JWT_SIGNING_SECRET must be at least 32 bytes");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey key) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey key, @Value("${pantrychef.jwt.issuer}") String issuer) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
