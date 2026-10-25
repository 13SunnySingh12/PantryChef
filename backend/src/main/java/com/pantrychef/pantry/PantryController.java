package com.pantrychef.pantry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.pantrychef.pantry.PantryService.EventResult;
import com.pantrychef.pantry.PantryService.Item;
import com.pantrychef.pantry.PantryService.ItemInput;
import com.pantrychef.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/pantry-items}. PATCH replaces the editable fields and requires the item's current version. */
@RestController
@RequestMapping("/api/v1/pantry-items")
public class PantryController {

	private final PantryService pantry;

	public PantryController(PantryService pantry) {
		this.pantry = pantry;
	}

	public record ItemRequest(@NotBlank @Size(max = 100) String name,
			@NotNull @Pattern(regexp = Vocabulary.CATEGORIES, message = "must be a supported category") String category,
			@NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal quantity,
			@NotNull @Pattern(regexp = Vocabulary.UNITS, message = "must be a supported unit") String unit,
			@PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal purchasePrice, LocalDate purchaseDate,
			LocalDate expiryDate, Long version) {

		ItemInput input() {
			return new ItemInput(this.name, this.category, this.quantity, this.unit, this.purchasePrice,
					this.purchaseDate, this.expiryDate);
		}
	}

	public record EventRequest(
			@NotNull @Pattern(regexp = Vocabulary.EVENT_TYPES, message = "must be used, finished or discarded") String type,
			@Positive @Digits(integer = 9, fraction = 3) BigDecimal quantity, @Size(max = 500) String notes) {
	}

	@GetMapping
	List<Item> list(@AuthenticationPrincipal Jwt token) {
		return this.pantry.list(CurrentUser.id(token));
	}

	@PostMapping
	ResponseEntity<Item> create(@AuthenticationPrincipal Jwt token, @Valid @RequestBody ItemRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.pantry.create(CurrentUser.id(token), request.input()));
	}

	@GetMapping("/{itemId}")
	Item get(@AuthenticationPrincipal Jwt token, @PathVariable UUID itemId) {
		return this.pantry.get(CurrentUser.id(token), itemId);
	}

	@PatchMapping("/{itemId}")
	Item update(@AuthenticationPrincipal Jwt token, @PathVariable UUID itemId, @Valid @RequestBody ItemRequest request) {
		return this.pantry.update(CurrentUser.id(token), itemId, request.input(), request.version());
	}

	@DeleteMapping("/{itemId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token, @PathVariable UUID itemId) {
		this.pantry.delete(CurrentUser.id(token), itemId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{itemId}/events")
	ResponseEntity<EventResult> recordEvent(@AuthenticationPrincipal Jwt token, @PathVariable UUID itemId,
			@Valid @RequestBody EventRequest request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		EventResult result = this.pantry.recordEvent(CurrentUser.id(token), itemId, request.type(), request.quantity(),
				request.notes(), idempotencyKey);
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

}
