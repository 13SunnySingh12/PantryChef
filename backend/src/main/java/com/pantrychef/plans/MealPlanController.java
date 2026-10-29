package com.pantrychef.plans;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.pantrychef.plans.MealPlanService.Generated;
import com.pantrychef.plans.MealPlanService.GroceryItem;
import com.pantrychef.plans.MealPlanService.Plan;
import com.pantrychef.plans.MealPlanService.Summary;
import com.pantrychef.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Meal plans (API-016–API-019) and grocery check-off (API-020); every route is scoped to the signed-in user. */
@RestController
public class MealPlanController {

	private final MealPlanService plans;

	public MealPlanController(MealPlanService plans) {
		this.plans = plans;
	}

	public record CheckRequest(@NotNull Boolean checked) {
	}

	/** Optional body (A-10); no body or no startDate means today in APP_TIMEZONE. */
	public record GenerateRequest(LocalDate startDate) {
	}

	@PostMapping("/api/v1/meal-plans")
	ResponseEntity<Plan> generate(@AuthenticationPrincipal Jwt token,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) GenerateRequest request) {
		Generated result = this.plans.generate(CurrentUser.id(token), idempotencyKey,
				request == null ? null : request.startDate());
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.plan());
	}

	@GetMapping("/api/v1/meal-plans")
	List<Summary> list(@AuthenticationPrincipal Jwt token) {
		return this.plans.list(CurrentUser.id(token));
	}

	@GetMapping("/api/v1/meal-plans/{planId}")
	Plan get(@AuthenticationPrincipal Jwt token, @PathVariable UUID planId) {
		return this.plans.get(CurrentUser.id(token), planId);
	}

	@PostMapping("/api/v1/meal-plans/{planId}/grocery-recalculation")
	Plan recalculate(@AuthenticationPrincipal Jwt token, @PathVariable UUID planId) {
		return this.plans.recalculateGrocery(CurrentUser.id(token), planId);
	}

	@PatchMapping("/api/v1/grocery-items/{itemId}")
	GroceryItem check(@AuthenticationPrincipal Jwt token, @PathVariable UUID itemId,
			@Valid @RequestBody CheckRequest request) {
		return this.plans.setChecked(CurrentUser.id(token), itemId, request.checked());
	}

}
