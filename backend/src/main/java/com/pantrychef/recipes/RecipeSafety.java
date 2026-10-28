package com.pantrychef.recipes;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.pantrychef.preferences.Eligibility;
import com.pantrychef.preferences.PreferencesController.Preferences;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Defense in depth (§8.6): whatever the AI service returns, a recipe is shown or planned only if it exists in our
 * database and passes the eligibility rule against the stored recipe data.
 */
@Component
public class RecipeSafety {

	public static final String DISCLAIMER = "Diet and allergen information comes from the recipe sources and a PantryChef "
			+ "ingredient list; it may be incomplete. PantryChef cannot guarantee that a recipe is safe for an "
			+ "allergy or medical condition. Always check ingredient labels.";

	private final JdbcClient db;

	public RecipeSafety(JdbcClient db) {
		this.db = db;
	}

	/** Recipe ids (as text from the AI service) that are stored and eligible for these preferences. */
	public Set<UUID> eligible(Collection<?> candidateIds, Preferences prefs) {
		Set<UUID> ids = new HashSet<>();
		for (Object id : candidateIds) {
			try {
				ids.add(UUID.fromString(String.valueOf(id)));
			}
			catch (IllegalArgumentException ex) {
				// not a recipe id at all: never eligible
			}
		}
		Set<UUID> eligible = new HashSet<>();
		if (ids.isEmpty()) {
			return eligible;
		}
		this.db.sql("SELECT r.id, r.diet_labels, r.allergen_metadata_complete,"
				+ " array(SELECT DISTINCT unnest(i.allergen_flags) FROM recipe_ingredients i WHERE i.recipe_id = r.id)"
				+ " AS flags FROM recipes r WHERE r.id = ANY(?)")
			.param(ids.toArray(UUID[]::new))
			.query((rs, n) -> {
				var recipe = new Eligibility.Recipe(Set.copyOf(Arrays.asList((String[]) rs.getArray("diet_labels").getArray())),
						rs.getBoolean("allergen_metadata_complete"),
						Set.copyOf(Arrays.asList((String[]) rs.getArray("flags").getArray())));
				if (Eligibility.reasons(recipe, prefs.diets(), prefs.allergens()).isEmpty()) {
					eligible.add(rs.getObject("id", UUID.class));
				}
				return null;
			})
			.list();
		return eligible;
	}

}
