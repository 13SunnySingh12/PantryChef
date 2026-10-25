package com.pantrychef.preferences;

import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import com.pantrychef.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/preferences}: the caller's diets and allergen exclusions (FR-011, FR-012, A-08). PUT replaces both
 * sets. These are applied server-side on every suggestion and plan; no request parameter can switch them off.
 */
@RestController
@RequestMapping("/api/v1/preferences")
public class PreferencesController {

	static final String DIETS = "vegetarian|vegan|gluten_free";

	static final String ALLERGENS = "peanuts|tree_nuts|dairy|eggs|gluten|soy|fish|shellfish|sesame";

	private final JdbcClient db;

	public PreferencesController(JdbcClient db) {
		this.db = db;
	}

	public record Preferences(
			@NotNull @Size(max = 20) List<@NotNull @Pattern(regexp = DIETS, message = "must be a supported diet") String> diets,
			@NotNull @Size(max = 20) List<@NotNull @Pattern(regexp = ALLERGENS,
					message = "must be a supported allergen") String> allergens) {
	}

	@GetMapping
	Preferences get(@AuthenticationPrincipal Jwt token) {
		return load(CurrentUser.id(token));
	}

	@PutMapping
	@Transactional
	Preferences replace(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Preferences request) {
		UUID user = CurrentUser.id(token);
		this.db.sql("DELETE FROM user_dietary_preferences WHERE user_id = ?").param(user).update();
		this.db.sql("DELETE FROM user_allergens WHERE user_id = ?").param(user).update();
		for (String diet : new TreeSet<>(request.diets())) {
			this.db.sql("INSERT INTO user_dietary_preferences (user_id, preference_code) VALUES (?, ?)")
				.params(user, diet)
				.update();
		}
		for (String allergen : new TreeSet<>(request.allergens())) {
			this.db.sql("INSERT INTO user_allergens (user_id, allergen_code) VALUES (?, ?)").params(user, allergen).update();
		}
		return load(user);
	}

	/** The stored preferences; also what recipe and plan code must apply. */
	public Preferences load(UUID user) {
		return new Preferences(
				this.db.sql("SELECT preference_code FROM user_dietary_preferences WHERE user_id = ? ORDER BY 1")
					.param(user)
					.query(String.class)
					.list(),
				this.db.sql("SELECT allergen_code FROM user_allergens WHERE user_id = ? ORDER BY 1")
					.param(user)
					.query(String.class)
					.list());
	}

}
