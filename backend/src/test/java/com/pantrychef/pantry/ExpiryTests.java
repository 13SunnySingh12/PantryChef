package com.pantrychef.pantry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

// A-01 boundary rule with a fixed date (QA-008).
class ExpiryTests {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

	@Test
	void boundaries() {
		assertThat(Expiry.status(null, TODAY)).isEqualTo("none");
		assertThat(Expiry.status(TODAY.minusDays(1), TODAY)).isEqualTo("expired");
		assertThat(Expiry.status(TODAY, TODAY)).isEqualTo("expiring_soon");
		assertThat(Expiry.status(TODAY.plusDays(3), TODAY)).isEqualTo("expiring_soon");
		assertThat(Expiry.status(TODAY.plusDays(4), TODAY)).isEqualTo("ok");
		assertThat(Expiry.daysUntil(TODAY.minusDays(2), TODAY)).isEqualTo(-2);
		assertThat(Expiry.daysUntil(null, TODAY)).isNull();
	}

	@Test
	void monthAndLeapYearBoundaries() {
		assertThat(Expiry.status(LocalDate.of(2028, 3, 2), LocalDate.of(2028, 2, 28))).isEqualTo("expiring_soon");
		assertThat(Expiry.status(LocalDate.of(2028, 3, 3), LocalDate.of(2028, 2, 28))).isEqualTo("ok");
	}

}
