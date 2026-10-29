package com.pantrychef.recipes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pantrychef.pantry.PantryService;
import com.pantrychef.pantry.PantryService.Item;
import com.pantrychef.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ML estimates (FR-013, FR-014). Labelled as estimates; never presented as facts. The waste-risk features come from
 * the caller's own stored item, never from the request.
 */
@RestController
public class EstimatesController {

	private final AiClient ai;

	private final PantryService pantry;

	public EstimatesController(AiClient ai, PantryService pantry) {
		this.ai = ai;
		this.pantry = pantry;
	}

	public record CuisineRequest(@NotNull @Size(min = 1, max = 100, message = "Enter 1 to 100 ingredients.")
			List<@NotBlank(message = "Ingredients can't be blank.") @Size(max = 100, message = "Use at most 100 characters per ingredient.") String> ingredients) {
	}

	@PostMapping("/api/v1/cuisine/classify")
	Map<String, Object> classify(@AuthenticationPrincipal Jwt token, @Valid @RequestBody CuisineRequest request) {
		return this.ai.post("/internal/v1/cuisine/classify", Map.of("ingredients", request.ingredients()));
	}

	@GetMapping("/api/v1/pantry-items/{itemId}/waste-risk")
	Map<String, Object> wasteRisk(@AuthenticationPrincipal Jwt token, @PathVariable UUID itemId) {
		Item item = this.pantry.get(CurrentUser.id(token), itemId);
		BigDecimal used = item.quantity().subtract(item.quantityRemaining())
			.divide(item.quantity(), 4, RoundingMode.HALF_UP);
		Map<String, Object> features = new HashMap<>(); // HashMap: daysToExpiry may be null
		features.put("daysToExpiry", item.daysUntilExpiry());
		features.put("quantityRemaining", item.quantityRemaining());
		features.put("unit", item.unit());
		features.put("category", item.category());
		features.put("usedFraction", used);
		return this.ai.post("/internal/v1/waste/predict", features);
	}

}
