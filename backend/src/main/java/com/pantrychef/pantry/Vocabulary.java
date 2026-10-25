package com.pantrychef.pantry;

/** Approved codes (A-04). Must match the domains in database/migrations/V1__core_schema.sql. */
public final class Vocabulary {

	public static final String CATEGORIES = "dairy|vegetables|fruits|grains|meat|seafood|bakery|beverages|condiments|frozen|snacks|other";

	public static final String UNITS = "g|kg|ml|l|piece|pack|bunch|dozen";

	public static final String EVENT_TYPES = "used|finished|discarded";

	private Vocabulary() {
	}

}
