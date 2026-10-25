package com.pantrychef.pantry;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The single expiry rule (A-01, A-02), on calendar dates in APP_TIMEZONE: {@code expired} when the expiry date is
 * before today, {@code expiring_soon} from today through today + 3 inclusive, {@code none} without an expiry
 * date (never treated as expired). Expiry only flags an item; it never marks it discarded.
 */
public final class Expiry {

	public static final int SOON_DAYS = 3;

	private Expiry() {
	}

	public static String status(LocalDate expiryDate, LocalDate today) {
		if (expiryDate == null) {
			return "none";
		}
		if (expiryDate.isBefore(today)) {
			return "expired";
		}
		return expiryDate.isAfter(today.plusDays(SOON_DAYS)) ? "ok" : "expiring_soon";
	}

	public static Long daysUntil(LocalDate expiryDate, LocalDate today) {
		return expiryDate == null ? null : ChronoUnit.DAYS.between(today, expiryDate);
	}

}
