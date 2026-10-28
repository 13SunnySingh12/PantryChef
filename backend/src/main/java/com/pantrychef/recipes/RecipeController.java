package com.pantrychef.recipes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.pantrychef.pantry.PantryService;
import com.pantrychef.pantry.PantryService.Usable;
import com.pantrychef.preferences.PreferencesController;
import com.pantrychef.preferences.PreferencesController.Preferences;
import com.pantrychef.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static final Logger log = LoggerFactory.getLogger(RecipeController.class);

	private final AiClient ai;

	private final PantryService pantry;

	private final PreferencesController preferences;

	private final RecipeSafety safety;

	public RecipeController(AiClient ai, PantryService pantry, PreferencesController preferences, RecipeSafety safety) {
		this.ai = ai;
		this.pantry = pantry;
		this.preferences = preferences;
		this.safety = safety;
	}

	@GetMapping("/api/v1/recipes/suggestions")
	@SuppressWarnings("unchecked")
	Map<String, Object> suggestions(@AuthenticationPrincipal Jwt token,
			@RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit) {
		UUID user = CurrentUser.id(token);
		List<Usable> stock = this.pantry.usableStock(user);
		Preferences prefs = this.preferences.load(user);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("disclaimer", RecipeSafety.DISCLAIMER);
		if (stock.isEmpty()) {
			response.put("suggestions", List.of());
			response.put("excludedByPreferences", 0);
			response.put("reason", "empty_pantry");
			return response;
		}
		Map<String, Object> answer = this.ai.post("/internal/v1/recipes/retrieve",
				Map.of("pantry", stock, "diets", prefs.diets(), "allergens", prefs.allergens(), "limit", limit));
		List<Map<String, Object>> results = (List<Map<String, Object>>) answer.getOrDefault("results", List.of());
		Set<UUID> eligible = this.safety.eligible(results.stream().map((r) -> r.get("recipeId")).toList(), prefs);
		List<Map<String, Object>> safe = results.stream()
			.filter((r) -> eligible.stream().anyMatch((id) -> id.toString().equals(String.valueOf(r.get("recipeId")))))
			.toList();
		if (safe.size() < results.size()) {
			log.warn("Dropped {} AI suggestions that failed the backend recipe check", results.size() - safe.size());
		}
		response.put("suggestions", safe);
		response.put("excludedByPreferences", answer.getOrDefault("excludedByPreferences", 0));
		response.put("reason", safe.isEmpty() ? answer.getOrDefault("reason", "no_safe_match") : null);
		return response;
	}

}
