package com.pantrychef.recipes;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.pantrychef.alerts.ExpiryAlerts;
import com.pantrychef.preferences.Eligibility;
import com.pantrychef.preferences.PreferencesController;
import com.pantrychef.preferences.PreferencesController.Preferences;
import com.pantrychef.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/recipes/suggestions} (FR-009–FR-012, FR-016). The pantry and the diet/allergen preferences
 * come only from the database for the signed-in user; no request parameter can change or relax them. Every recipe
 * the AI service returns is re-checked here against the stored recipe data before it is shown.
 */
@RestController
public class RecipeController {

	static final String DISCLAIMER = "Diet and allergen information comes from the recipe sources and a PantryChef "
			+ "ingredient list; it may be incomplete. PantryChef cannot guarantee that a recipe is safe for an "
			+ "allergy or medical condition. Always check ingredient labels.";

	private static final Logger log = LoggerFactory.getLogger(RecipeController.class);

	private final JdbcClient db;

	private final AiClient ai;

	private final PreferencesController preferences;

	private final ExpiryAlerts dates;

	public RecipeController(JdbcClient db, AiClient ai, PreferencesController preferences, ExpiryAlerts dates) {
		this.db = db;
		this.ai = ai;
		this.preferences = preferences;
		this.dates = dates;
	}

	public record PantryIngredient(String name, Long daysToExpiry) {
	}

	@GetMapping("/api/v1/recipes/suggestions")
	@SuppressWarnings("unchecked")
	Map<String, Object> suggestions(@AuthenticationPrincipal Jwt token,
			@RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit) {
		UUID user = CurrentUser.id(token);
		LocalDate today = this.dates.today();
		// Usable stock only: not deleted, something left, not past its expiry date.
		List<PantryIngredient> pantry = this.db
			.sql("SELECT name, expiry_date FROM pantry_items WHERE user_id = ? AND deleted_at IS NULL"
					+ " AND quantity_remaining > 0 AND (expiry_date IS NULL OR expiry_date >= ?) ORDER BY id")
			.params(user, today)
			.query((rs, n) -> {
				LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
				return new PantryIngredient(rs.getString("name"),
						expiry == null ? null : ChronoUnit.DAYS.between(today, expiry));
			})
			.list();
		Preferences prefs = this.preferences.load(user);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("disclaimer", DISCLAIMER);
		if (pantry.isEmpty()) {
			response.put("suggestions", List.of());
			response.put("excludedByPreferences", 0);
			response.put("reason", "empty_pantry");
			return response;
		}
		Map<String, Object> answer = this.ai.post("/internal/v1/recipes/retrieve",
				Map.of("pantry", pantry, "diets", prefs.diets(), "allergens", prefs.allergens(), "limit", limit));
		List<Map<String, Object>> results = (List<Map<String, Object>>) answer.getOrDefault("results", List.of());
		List<Map<String, Object>> safe = recheck(results, prefs);
		if (safe.size() < results.size()) {
			log.warn("Dropped {} AI suggestions that failed the backend recipe check", results.size() - safe.size());
		}
		response.put("suggestions", safe);
		response.put("excludedByPreferences", answer.getOrDefault("excludedByPreferences", 0));
		response.put("reason", safe.isEmpty() ? answer.getOrDefault("reason", "no_safe_match") : null);
		return response;
	}

	/** Defense in depth (§8.6): keep only recipes that exist in our database and pass the eligibility rule there. */
	private List<Map<String, Object>> recheck(List<Map<String, Object>> results, Preferences prefs) {
		Map<Map<String, Object>, UUID> ids = new LinkedHashMap<>();
		for (Map<String, Object> result : results) {
			try {
				ids.put(result, UUID.fromString(String.valueOf(result.get("recipeId"))));
			}
			catch (IllegalArgumentException ex) {
				// not a recipe id at all: dropped
			}
		}
		Map<UUID, Eligibility.Recipe> stored = new HashMap<>();
		if (!ids.isEmpty()) {
			this.db.sql("SELECT r.id, r.diet_labels, r.allergen_metadata_complete,"
					+ " array(SELECT DISTINCT unnest(i.allergen_flags) FROM recipe_ingredients i WHERE i.recipe_id = r.id)"
					+ " AS flags FROM recipes r WHERE r.id = ANY(?)")
				.param(ids.values().toArray(UUID[]::new))
				.query((rs, n) -> stored.put(rs.getObject("id", UUID.class),
						new Eligibility.Recipe(Set.copyOf(Arrays.asList((String[]) rs.getArray("diet_labels").getArray())),
								rs.getBoolean("allergen_metadata_complete"),
								Set.copyOf(Arrays.asList((String[]) rs.getArray("flags").getArray())))))
				.list();
		}
		List<Map<String, Object>> safe = new ArrayList<>();
		ids.forEach((result, id) -> {
			Eligibility.Recipe recipe = stored.get(id);
			if (recipe != null && Eligibility.reasons(recipe, prefs.diets(), prefs.allergens()).isEmpty()) {
				safe.add(result);
			}
		});
		return safe;
	}

}
