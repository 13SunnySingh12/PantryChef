package com.pantrychef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

// Verifies the V1 migration applies cleanly and that the database itself rejects invalid data (NFR-010, QA-025).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaConstraintsTests {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Flyway flyway;

	@Test
	void migrationsApplyAndRerunIsNoOp() {
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
		assertThat(flyway.migrate().migrationsExecuted).isZero();
	}

	@Test
	void usersRequireUniqueNormalizedEmail() {
		UUID id = user("dup@example.com");
		assertThat(id).isNotNull();
		rejects("INSERT INTO users (email, password_hash) VALUES ('dup@example.com', 'h')");
		rejects("INSERT INTO users (email, password_hash) VALUES ('Mixed@Example.com', 'h')");
		rejects("INSERT INTO users (email, password_hash) VALUES (' pad@example.com', 'h')");
	}

	@Test
	void pantryItemsRejectInvalidValues() {
		UUID u = user(email());
		assertThatCode(() -> item(u, "'Milk'", "'dairy'", "2", "2", "'l'", "'INR'", "NULL", "NULL")).doesNotThrowAnyException();
		rejectsItem(u, "'  '", "'dairy'", "2", "2", "'l'", "'INR'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'toys'", "2", "2", "'l'", "'INR'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "0", "0", "'l'", "'INR'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "-1", "0", "'l'", "'INR'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "2", "3", "'l'", "'INR'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "2", "-1", "'l'", "'INR'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "2", "2", "'cups'", "'INR'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "2", "2", "'l'", "'inr'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "2", "2", "'l'", "'INR'", "'NaN'", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "'NaN'", "'NaN'", "'l'", "'INR'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "'Infinity'", "1", "'l'", "'INR'", "NULL", "NULL");
		rejectsItem(u, "'Milk'", "'dairy'", "2", "2", "'l'", "'INR'", "NULL", "DATE '2026-10-30'"); // bought after expiry 2026-10-27
		rejects("INSERT INTO pantry_items (user_id, name, category, quantity_initial, quantity_remaining, unit, currency_code, purchase_price)"
				+ " VALUES (?, 'Milk', 'dairy', 2, 2, 'l', 'INR', -5)", u);
	}

	@Test
	void eventsRequirePositiveQuantityKnownTypeAndUniqueIdempotencyKeyPerUser() {
		UUID a = user(email());
		UUID b = user(email());
		UUID itemA = item(a, "'Rice'", "'grains'", "1", "1", "'kg'", "'INR'", "NULL", "NULL");
		UUID itemB = item(b, "'Rice'", "'grains'", "1", "1", "'kg'", "'INR'", "NULL", "NULL");
		String insert = "INSERT INTO pantry_item_events (user_id, pantry_item_id, event_type, quantity, unit, idempotency_key)"
				+ " VALUES (?, ?, ?, ?, 'kg', ?)";
		jdbc.update(insert, a, itemA, "used", 0.5, "key-1");
		jdbc.update(insert, b, itemB, "used", 0.5, "key-1");
		rejects(insert, a, itemA, "used", 0.2, "key-1");
		rejects(insert, a, itemA, "used", 0, null);
		rejects(insert, a, itemA, "eaten", 0.1, null);
	}

	@Test
	void onlyOneOpenAlertPerItem() {
		UUID u = user(email());
		UUID it = item(u, "'Spinach'", "'vegetables'", "1", "1", "'bunch'", "'INR'", "NULL", "DATE '2026-10-27'");
		String insert = "INSERT INTO expiry_alerts (user_id, pantry_item_id, alert_type, alert_date) VALUES (?, ?, ?, DATE '2026-10-25')";
		jdbc.update(insert, u, it, "expiring_soon");
		rejects(insert, u, it, "expired");
		jdbc.update("UPDATE expiry_alerts SET resolved_at = now() WHERE pantry_item_id = ?", it);
		jdbc.update(insert, u, it, "expired");
		rejects(insert, u, it, "overdue");
	}

	@Test
	void preferencesUseApprovedVocabularyWithoutDuplicates() {
		UUID u = user(email());
		jdbc.update("INSERT INTO user_dietary_preferences (user_id, preference_code) VALUES (?, 'vegan')", u);
		jdbc.update("INSERT INTO user_allergens (user_id, allergen_code) VALUES (?, 'peanuts')", u);
		rejects("INSERT INTO user_dietary_preferences (user_id, preference_code) VALUES (?, 'vegan')", u);
		rejects("INSERT INTO user_dietary_preferences (user_id, preference_code) VALUES (?, 'keto')", u);
		rejects("INSERT INTO user_allergens (user_id, allergen_code) VALUES (?, 'peanuts')", u);
		rejects("INSERT INTO user_allergens (user_id, allergen_code) VALUES (?, 'nightshade')", u);
	}

	@Test
	void recipesRequireUniqueSourceAndApprovedLabels() {
		UUID r = recipe("src-1");
		rejects("INSERT INTO recipes (source_name, source_key, source_license, dataset_version, title)"
				+ " VALUES ('fixture', 'src-1', 'test', 'v1', 'Dup')");
		rejects("INSERT INTO recipes (source_name, source_key, source_license, dataset_version, title, diet_labels)"
				+ " VALUES ('fixture', 'src-2', 'test', 'v1', 'Bad', '{keto}')");
		assertThat(jdbc.queryForObject("SELECT allergen_metadata_complete FROM recipes WHERE id = ?", Boolean.class, r)).isFalse();
		String ingredient = "INSERT INTO recipe_ingredients (recipe_id, ingredient_name, normalized_ingredient, unit, allergen_flags, sort_order)"
				+ " VALUES (?, 'Peanut butter', 'peanut butter', ?, ?::text[], ?)";
		jdbc.update(ingredient, r, null, "{peanuts}", 0);
		rejects(ingredient, r, null, "{peanuts}", 0);
		rejects(ingredient, r, null, "{glitter}", 1);
		rejects(ingredient, r, "cups", "{}", 2);
	}

	@Test
	void mealPlansHaveSevenDaysAndWellFormedSlots() {
		UUID u = user(email());
		UUID r = recipe("plan-recipe");
		rejects("INSERT INTO meal_plans (user_id, start_date, end_date, status) VALUES (?, DATE '2026-10-26', DATE '2026-10-31', 'complete')", u);
		UUID plan = jdbc.queryForObject("INSERT INTO meal_plans (user_id, start_date, end_date, status)"
				+ " VALUES (?, DATE '2026-10-26', DATE '2026-11-01', 'partial') RETURNING id", UUID.class, u);
		String entry = "INSERT INTO meal_plan_entries (meal_plan_id, recipe_id, plan_date, meal_period, servings, unavailable_reason)"
				+ " VALUES (?, ?, DATE '2026-10-26', ?, ?, ?)";
		jdbc.update(entry, plan, r, "breakfast", 2, null);
		jdbc.update(entry, plan, null, "lunch", null, "No eligible recipe");
		rejects(entry, plan, r, "breakfast", 2, null);
		rejects(entry, plan, r, "dinner", null, null);
		rejects(entry, plan, null, "dinner", null, null);
		rejects(entry, plan, r, "brunch", 1, null);
	}

	@Test
	void groceryItemsKeepQuantitiesExplicitAndCheckStateConsistent() {
		UUID u = user(email());
		UUID plan = jdbc.queryForObject("INSERT INTO meal_plans (user_id, start_date, end_date, status)"
				+ " VALUES (?, DATE '2026-10-26', DATE '2026-11-01', 'complete') RETURNING id", UUID.class, u);
		String insert = "INSERT INTO grocery_items (meal_plan_id, normalized_ingredient, display_name, quantity_needed, unit, quantity_unresolved, checked, checked_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
		jdbc.update(insert, plan, "rice", "Rice", 500, "g", false, false, null);
		jdbc.update(insert, plan, "saffron", "Saffron", null, null, true, false, null);
		rejects(insert, plan, "saffron", "Saffron", null, null, true, false, null);
		rejects(insert, plan, "milk", "Milk", null, null, false, false, null);
		rejects(insert, plan, "eggs", "Eggs", 6, "piece", false, true, null);
		rejects(insert, plan, "oil", "Oil", 0, "ml", false, false, null);
	}

	@Test
	void deletingUserRemovesOwnedRows() {
		UUID u = user(email());
		UUID it = item(u, "'Bread'", "'bakery'", "1", "1", "'piece'", "'INR'", "NULL", "NULL");
		jdbc.update("DELETE FROM users WHERE id = ?", u);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pantry_items WHERE id = ?", Integer.class, it)).isZero();
	}

	private UUID user(String email) {
		return jdbc.queryForObject("INSERT INTO users (email, password_hash) VALUES (?, 'hash') RETURNING id", UUID.class, email);
	}

	private static String email() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	// Values are SQL literals from this test only (never user input).
	private UUID item(UUID user, String name, String category, String initial, String remaining, String unit,
			String currency, String purchasePrice, String purchaseDate) {
		return jdbc.queryForObject("INSERT INTO pantry_items (user_id, name, category, quantity_initial, quantity_remaining, unit,"
				+ " currency_code, purchase_price, purchase_date, expiry_date) VALUES (?, " + name + ", " + category + ", " + initial
				+ ", " + remaining + ", " + unit + ", " + currency + ", " + purchasePrice + ", " + purchaseDate
				+ ", DATE '2026-10-27') RETURNING id", UUID.class, user);
	}

	private void rejectsItem(UUID user, String... values) {
		assertThatThrownBy(() -> item(user, values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID recipe(String key) {
		return jdbc.queryForObject("INSERT INTO recipes (source_name, source_key, source_license, dataset_version, title)"
				+ " VALUES ('fixture', ?, 'test', 'v1', 'Fixture recipe') RETURNING id", UUID.class, key);
	}

	private void rejects(String sql, Object... args) {
		assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataIntegrityViolationException.class);
	}

}
