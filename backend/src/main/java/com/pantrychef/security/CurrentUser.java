package com.pantrychef.security;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** The only source of user identity: the subject of a validated token, never a request field. */
public final class CurrentUser {

	private CurrentUser() {
	}

	public static UUID id(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
