package com.pantrychef.plans;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.pantrychef.alerts.ExpiryAlerts;
import com.pantrychef.pantry.PantryService;
import com.pantrychef.pantry.PantryService.Usable;
import com.pantrychef.preferences.PreferencesController;
import com.pantrychef.preferences.PreferencesController.Preferences;
import com.pantrychef.recipes.AiClient;
import com.pantrychef.recipes.RecipeSafety;
import com.pantrychef.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seven-day meal plans and grocery lists (FR-015–FR-018, A-10). The AI service proposes slots; every proposed
 * recipe is re-checked against the stored recipe data before anything is saved. Each generation creates a new plan
 * (earlier plans and their checked grocery items are never overwritten). Grocery lists are a snapshot; an explicit
 * recalculation replaces it from the current pantry and keeps items already checked off.
 */
@Service
public class MealPlanService {

	static final int DAYS = 7;

	static final String REMOVED_BY_CHECK = "A proposed recipe failed the safety re-check and was left out.";

	private final JdbcClient db;

	private final AiClient ai;

	private final PantryService pantry;

	private final PreferencesController preferences;

	private final RecipeSafety safety;

	private final ExpiryAlerts dates;

	private final TransactionTemplate tx;

	public MealPlanService(JdbcClient db, AiClient ai, PantryService pantry, PreferencesController preferences,
			RecipeSafety safety, ExpiryAlerts dates, TransactionTemplate tx) {
		this.db = db;
		this.ai = ai;
		this.pantry = pantry;
		this.preferences = preferences;
		this.safety = safety;
		this.dates = dates;
		this.tx = tx;
	}

	public record Entry(LocalDate date, String mealPeriod, UUID recipeId, String title, BigDecimal servings,
			String unavailableReason, String sourceName, String sourceUrl, String attribution) {
	}

	public record GroceryItem(UUID id, String ingredient, BigDecimal quantity, String unit, boolean unresolved,
			boolean checked, Instant checkedAt) {
	}

	public record Plan(UUID id, LocalDate startDate, LocalDate endDate, String status, Instant createdAt,
			Instant groceryCalculatedAt, List<Entry> entries, List<GroceryItem> grocery, String disclaimer) {
	}

	public record Summary(UUID id, LocalDate startDate, LocalDate endDate, String status, int plannedSlots,
			Instant createdAt) {
	}

	public record Generated(Plan plan, boolean replayed) {
	}

	/** Creates a plan starting today (APP_TIMEZONE). A repeated idempotency key returns the original plan. */
	@SuppressWarnings("unchecked")
	public Generated generate(UUID user, String idempotencyKey) {
		if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 100)) {
			throw ApiException.invalidField("Idempotency-Key", "Must be 1 to 100 characters.");
		}
		Optional<UUID> previous = idempotencyKey == null ? Optional.empty() : planForKey(user, idempotencyKey);
		if (previous.isPresent()) {
			return new Generated(get(user, previous.get()), true);
		}
		List<Usable> stock = this.pantry.usableStock(user);
		Preferences prefs = this.preferences.load(user);
		Map<String, Object> answer = this.ai.post("/internal/v1/meal-plans/generate",
				Map.of("pantry", stock, "diets", prefs.diets(), "allergens", prefs.allergens(), "days", DAYS));
		List<Map<String, Object>> slots = (List<Map<String, Object>>) answer.get("entries");
		if (slots == null || slots.size() != DAYS * 3) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR", "Meal planning failed. Try again later.");
		}
		Set<UUID> safe = this.safety.eligible(slots.stream().map((s) -> s.get("recipeId")).filter((id) -> id != null)
			.toList(), prefs);
		boolean removedAny = slots.stream().anyMatch((s) -> s.get("recipeId") != null && !isSafe(s, safe));
		Map<String, Object> grocery = removedAny ? groceryFor(safe, stock)
				: (Map<String, Object>) answer.get("grocery");
		LocalDate start = this.dates.today();
		UUID planId = this.tx.execute((status) -> {
			boolean complete = slots.stream().allMatch((s) -> isSafe(s, safe));
			Optional<UUID> created = this.db
				.sql("INSERT INTO meal_plans (user_id, start_date, end_date, status, generation_version, idempotency_key,"
						+ " grocery_calculated_at) VALUES (?, ?, ?, ?, ?, ?, now())"
						+ " ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id")
				.params(user, start, start.plusDays(DAYS - 1), complete ? "complete" : "partial",
						answer.get("generationVersion"), idempotencyKey)
				.query(UUID.class)
				.optional();
			if (created.isEmpty()) {
				return null; // the same key won a concurrent race; return that plan below
			}
			for (Map<String, Object> slot : slots) {
				boolean planned = isSafe(slot, safe);
				this.db.sql("INSERT INTO meal_plan_entries (meal_plan_id, recipe_id, plan_date, meal_period, servings,"
						+ " unavailable_reason) VALUES (?, ?, ?, ?, ?, ?)")
					.params(created.get(), planned ? UUID.fromString((String) slot.get("recipeId")) : null,
							start.plusDays(((Number) slot.get("day")).longValue()), slot.get("mealPeriod"),
							planned ? slot.get("servings") : null,
							planned ? null : slot.get("recipeId") == null ? slot.get("reason") : REMOVED_BY_CHECK)
					.update();
			}
			saveGrocery(created.get(), grocery, Set.of());
			return created.get();
		});
		if (planId == null) {
			return new Generated(get(user, planForKey(user, idempotencyKey).orElseThrow()), true);
		}
		return new Generated(get(user, planId), false);
	}

	/** Rebuilds the grocery list from the current pantry; items already checked off stay checked. */
	public Plan recalculateGrocery(UUID user, UUID planId) {
		Plan plan = get(user, planId);
		Set<UUID> recipes = new HashSet<>();
		plan.entries().stream().filter((e) -> e.recipeId() != null).forEach((e) -> recipes.add(e.recipeId()));
		Map<String, Object> grocery = groceryFor(recipes, this.pantry.usableStock(user));
		this.tx.executeWithoutResult((status) -> {
			Set<String> checked = new HashSet<>(this.db
				.sql("SELECT normalized_ingredient || '|' || coalesce(unit, '') FROM grocery_items"
						+ " WHERE meal_plan_id = ? AND checked")
				.param(planId)
				.query(String.class)
				.list());
			this.db.sql("DELETE FROM grocery_items WHERE meal_plan_id = ?").param(planId).update();
			saveGrocery(planId, grocery, checked);
			this.db.sql("UPDATE meal_plans SET grocery_calculated_at = now() WHERE id = ?").param(planId).update();
		});
		return get(user, planId);
	}

	public GroceryItem setChecked(UUID user, UUID itemId, boolean checked) {
		return this.db
			.sql("UPDATE grocery_items g SET checked = ?, checked_at = CASE WHEN ? THEN now() END FROM meal_plans p"
					+ " WHERE g.id = ? AND g.meal_plan_id = p.id AND p.user_id = ?"
					+ " RETURNING g.id, g.display_name, g.quantity_needed, g.unit, g.quantity_unresolved, g.checked,"
					+ " g.checked_at")
			.params(checked, checked, itemId, user)
			.query(this::groceryItem)
			.optional()
			.orElseThrow(ApiException::notFound);
	}

	public List<Summary> list(UUID user) {
		return this.db
			.sql("SELECT p.id, p.start_date, p.end_date, p.status, p.created_at,"
					+ " (SELECT count(*) FROM meal_plan_entries e WHERE e.meal_plan_id = p.id AND e.recipe_id IS NOT NULL)"
					+ " AS planned FROM meal_plans p WHERE p.user_id = ? ORDER BY p.created_at DESC, p.id LIMIT 50")
			.param(user)
			.query((rs, n) -> new Summary(rs.getObject("id", UUID.class), rs.getObject("start_date", LocalDate.class),
					rs.getObject("end_date", LocalDate.class), rs.getString("status"), rs.getInt("planned"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.list();
	}

	public Plan get(UUID user, UUID planId) {
		var header = this.db
			.sql("SELECT start_date, end_date, status, created_at, grocery_calculated_at FROM meal_plans"
					+ " WHERE id = ? AND user_id = ?")
			.params(planId, user)
			.query((rs, n) -> new Object[] { rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class),
					rs.getString(3), rs.getObject(4, OffsetDateTime.class).toInstant(),
					Optional.ofNullable(rs.getObject(5, OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null) })
			.optional()
			.orElseThrow(ApiException::notFound);
		List<Entry> entries = this.db
			.sql("SELECT e.plan_date, e.meal_period, e.recipe_id, r.title, e.servings, e.unavailable_reason,"
					+ " r.source_name, r.source_url, r.attribution FROM meal_plan_entries e"
					+ " LEFT JOIN recipes r ON r.id = e.recipe_id WHERE e.meal_plan_id = ?"
					+ " ORDER BY e.plan_date, array_position(ARRAY['breakfast', 'lunch', 'dinner'], e.meal_period)")
			.param(planId)
			.query((rs, n) -> new Entry(rs.getObject("plan_date", LocalDate.class), rs.getString("meal_period"),
					rs.getObject("recipe_id", UUID.class), rs.getString("title"), rs.getBigDecimal("servings"),
					rs.getString("unavailable_reason"), rs.getString("source_name"), rs.getString("source_url"),
					rs.getString("attribution")))
			.list();
		List<GroceryItem> grocery = this.db
			.sql("SELECT id, display_name, quantity_needed, unit, quantity_unresolved, checked, checked_at"
					+ " FROM grocery_items WHERE meal_plan_id = ? ORDER BY quantity_unresolved, display_name, id")
			.param(planId)
			.query(this::groceryItem)
			.list();
		return new Plan(planId, (LocalDate) header[0], (LocalDate) header[1], (String) header[2], (Instant) header[3],
				(Instant) header[4], entries, grocery, RecipeSafety.DISCLAIMER);
	}

	private Optional<UUID> planForKey(UUID user, String key) {
		return this.db.sql("SELECT id FROM meal_plans WHERE user_id = ? AND idempotency_key = ?")
			.params(user, key)
			.query(UUID.class)
			.optional();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> groceryFor(Set<UUID> recipes, List<Usable> stock) {
		return this.ai.post("/internal/v1/grocery/compute", Map.of("recipeIds", recipes, "pantry", stock));
	}

	@SuppressWarnings("unchecked")
	private void saveGrocery(UUID planId, Map<String, Object> grocery, Set<String> checkedKeys) {
		List<Map<String, Object>> items = grocery == null ? List.of()
				: (List<Map<String, Object>>) grocery.getOrDefault("items", List.of());
		for (Map<String, Object> item : items) {
			String key = (String) item.get("ingredient");
			String unit = (String) item.get("unit");
			boolean checked = checkedKeys.contains(key + "|" + (unit == null ? "" : unit));
			this.db.sql("INSERT INTO grocery_items (meal_plan_id, normalized_ingredient, display_name, quantity_needed,"
					+ " unit, quantity_unresolved, checked, checked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
				.params(planId, key, key, item.get("quantity"), unit, Boolean.TRUE.equals(item.get("unresolved")),
						checked, checked ? OffsetDateTime.now() : null)
				.update();
		}
	}

	private static boolean isSafe(Map<String, Object> slot, Set<UUID> safe) {
		Object id = slot.get("recipeId");
		return id != null && safe.stream().anyMatch((s) -> s.toString().equals(id));
	}

	private GroceryItem groceryItem(ResultSet rs, int row) throws SQLException {
		return new GroceryItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
				rs.getBoolean(5), rs.getBoolean(6),
				Optional.ofNullable(rs.getObject(7, OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null));
	}

}
