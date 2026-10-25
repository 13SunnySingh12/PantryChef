package com.pantrychef.preferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether a recipe may be shown to or planned for a user (FR-011, FR-012, A-08). Fails closed:
 * <ul>
 * <li>an excluded allergen needs complete allergen metadata and no ingredient carrying it;</li>
 * <li>{@code vegan} needs a vegan label and no dairy/egg/fish/shellfish ingredient;</li>
 * <li>{@code vegetarian} needs a vegetarian or vegan label and no fish/shellfish ingredient;</li>
 * <li>{@code gluten_free} needs no gluten ingredient, plus a label or complete allergen metadata.</li>
 * </ul>
 * Mirrored by {@code ai-service/app/eligibility.py}; both are tested against
 * {@code database/fixtures/eligibility_cases.json}.
 */
public final class Eligibility {

	private static final Set<String> NOT_VEGAN = Set.of("dairy", "eggs", "fish", "shellfish");

	private static final Set<String> NOT_VEGETARIAN = Set.of("fish", "shellfish");

	private Eligibility() {
	}

	public record Recipe(Set<String> dietLabels, boolean allergenMetadataComplete, Set<String> ingredientAllergens) {
	}

	/** Reasons the recipe is not allowed, in a stable order; empty means eligible. */
	public static List<String> reasons(Recipe recipe, Collection<String> diets, Collection<String> allergens) {
		List<String> reasons = new ArrayList<>();
		if (!allergens.isEmpty() && !recipe.allergenMetadataComplete()) {
			reasons.add("allergen_data_incomplete");
		}
		for (String allergen : new TreeSet<>(allergens)) {
			if (recipe.ingredientAllergens().contains(allergen)) {
				reasons.add("allergen_present:" + allergen);
			}
		}
		for (String diet : new TreeSet<>(diets)) {
			if (!meetsDiet(recipe, diet)) {
				reasons.add("diet_not_met:" + diet);
			}
		}
		return reasons;
	}

	private static boolean meetsDiet(Recipe recipe, String diet) {
		Set<String> labels = recipe.dietLabels();
		Set<String> flags = recipe.ingredientAllergens();
		return switch (diet) {
			case "vegan" -> labels.contains("vegan") && flags.stream().noneMatch(NOT_VEGAN::contains);
			case "vegetarian" -> (labels.contains("vegetarian") || labels.contains("vegan"))
					&& flags.stream().noneMatch(NOT_VEGETARIAN::contains);
			case "gluten_free" -> !flags.contains("gluten")
					&& (labels.contains("gluten_free") || recipe.allergenMetadataComplete());
			default -> false;
		};
	}

}
