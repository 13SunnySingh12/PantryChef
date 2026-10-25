package com.pantrychef.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// FR-011, FR-012, QA-010: same cases as ai-service/tests/test_eligibility.py.
class EligibilityTests {

	static Stream<Arguments> cases() throws IOException {
		String json = Files.readString(Path.of("../database/fixtures/eligibility_cases.json"));
		List<Map<String, Object>> cases = JsonPath.read(json, "$.cases");
		return cases.stream().map((c) -> Arguments.of(c.get("name"), c));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	@SuppressWarnings("unchecked")
	void matchesSharedFixture(String name, Map<String, Object> c) {
		Map<String, Object> r = (Map<String, Object>) c.get("recipe");
		var recipe = new Eligibility.Recipe(Set.copyOf((List<String>) r.get("dietLabels")),
				(Boolean) r.get("allergenMetadataComplete"), Set.copyOf((List<String>) r.get("ingredientAllergens")));
		assertThat(Eligibility.reasons(recipe, (List<String>) c.get("diets"), (List<String>) c.get("allergens")))
			.isEqualTo(c.get("reasons"));
	}

	@Test
	void unknownDietCodeIsNeverMet() {
		var recipe = new Eligibility.Recipe(Set.of("vegan"), true, Set.of());
		assertThat(Eligibility.reasons(recipe, List.of("keto"), List.of())).containsExactly("diet_not_met:keto");
	}

}
