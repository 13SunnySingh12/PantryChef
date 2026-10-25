package com.pantrychef.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RateLimiterTests {

	private Instant now = Instant.parse("2026-10-25T10:00:00Z");

	private final Clock clock = new Clock() {
		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return RateLimiterTests.this.now;
		}
	};

	@Test
	void limitsWithinWindowAndRecoversAfterIt() {
		RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(15), this.clock);
		limiter.record("k");
		assertThat(limiter.isLimited("k")).isFalse();
		limiter.record("k");
		assertThat(limiter.isLimited("k")).isTrue();
		assertThat(limiter.isLimited("other")).isFalse();
		this.now = this.now.plus(Duration.ofMinutes(15));
		assertThat(limiter.isLimited("k")).isFalse();
	}

	@Test
	void resetClearsAKey() {
		RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), this.clock);
		limiter.record("k");
		limiter.reset("k");
		assertThat(limiter.isLimited("k")).isFalse();
	}

}
