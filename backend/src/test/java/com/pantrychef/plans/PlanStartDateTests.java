package com.pantrychef.plans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.pantrychef.alerts.ExpiryAlerts;
import com.pantrychef.web.ApiException;
import org.junit.jupiter.api.Test;

// A-10: the start date is a calendar day in APP_TIMEZONE, never the UTC date of the server clock.
class PlanStartDateTests {

	private static LocalDate todayAt(String instant) {
		return new ExpiryAlerts(null, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), ZoneId.of("Asia/Kolkata"))
			.today();
	}

	@Test
	void defaultsToTodayInTheAppTimezoneAcrossTheUtcDateBoundary() {
		LocalDate today = todayAt("2026-10-29T19:00:00Z"); // 00:30 on 30 October in Asia/Kolkata
		assertThat(today).isEqualTo(LocalDate.of(2026, 10, 30));
		assertThat(MealPlanService.startDate(null, today)).isEqualTo(LocalDate.of(2026, 10, 30));
		assertThatThrownBy(() -> MealPlanService.startDate(LocalDate.of(2026, 10, 29), today))
			.isInstanceOf(ApiException.class); // the UTC date is already "yesterday" here
		assertThat(MealPlanService.startDate(null, todayAt("2026-10-29T18:29:59Z"))).isEqualTo(LocalDate.of(2026, 10, 29));
	}

	@Test
	void acceptsTodayUpToSevenDaysAheadAndNothingElse() {
		LocalDate today = LocalDate.of(2026, 10, 29);
		assertThat(MealPlanService.startDate(today, today)).isEqualTo(today);
		assertThat(MealPlanService.startDate(today.plusDays(7), today)).isEqualTo(today.plusDays(7));
		assertThatThrownBy(() -> MealPlanService.startDate(today.minusDays(1), today)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> MealPlanService.startDate(today.plusDays(8), today)).isInstanceOf(ApiException.class);
	}

}
