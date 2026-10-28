package com.pantrychef.dashboard;

import java.util.UUID;

import com.pantrychef.dashboard.DashboardService.Summary;
import com.pantrychef.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/dashboard/summary?days=30&planId=…} (API-021, covers API-022): the caller's food and money
 * saved over the last {@code days} days, a daily series for the line chart, and per-day nutrition of the latest (or
 * given) meal plan for the bar chart.
 */
@RestController
public class DashboardController {

	private final DashboardService dashboard;

	private final String currency;

	public DashboardController(DashboardService dashboard, @Value("${pantrychef.currency}") String currency) {
		this.dashboard = dashboard;
		this.currency = currency;
	}

	@GetMapping("/api/v1/dashboard/summary")
	Summary summary(@AuthenticationPrincipal Jwt token, @RequestParam(defaultValue = "30") @Min(1) @Max(366) int days,
			@RequestParam(required = false) UUID planId) {
		return this.dashboard.summary(CurrentUser.id(token), days, this.currency, planId);
	}

}
