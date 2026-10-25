package com.pantrychef.alerts;

import java.util.List;

import com.pantrychef.alerts.ExpiryAlerts.Alert;
import com.pantrychef.security.CurrentUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/expiry-alerts}: the caller's open alerts, most urgent (earliest expiry) first. */
@RestController
public class ExpiryAlertController {

	private final ExpiryAlerts alerts;

	public ExpiryAlertController(ExpiryAlerts alerts) {
		this.alerts = alerts;
	}

	@GetMapping("/api/v1/expiry-alerts")
	List<Alert> list(@AuthenticationPrincipal Jwt token) {
		return this.alerts.list(CurrentUser.id(token));
	}

}
