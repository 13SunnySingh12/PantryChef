package com.pantrychef.recipes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.pantrychef.AiStub;
import com.pantrychef.PantryChefTest;
import com.pantrychef.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// FR-009–FR-012, FR-016 orchestration; NFR-005/QA-017 (AI down, slow, failing); QA-022 (service token sent);
// §8.6 defense in depth (AI output re-checked against stored recipes).
@PantryChefTest
class RecipeSuggestionTests {

	static final String TOKEN = "test-only-service-token-0123456789-abcdef";

	static final AiStub AI = new AiStub();

	static final String RETRIEVE = "/internal/v1/recipes/retrieve";

	@DynamicPropertySource
	static void ai(DynamicPropertyRegistry registry) {
		registry.add("pantrychef.ai.base-url", AI::baseUrl);
		registry.add("pantrychef.ai.service-token", () -> TOKEN);
		registry.add("pantrychef.ai.read-timeout", () -> "PT1S");
	}

	@AfterAll
	static void stop() {
		AI.server.stop(0);
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient db;

	String alice;

	@BeforeEach
	void setUp() throws Exception {
		AI.reset();
		this.alice = TestUsers.signUp(this.mvc);
	}

	@Test
	void emptyPantryNeedsNoAiCall() throws Exception {
		suggestions(this.alice).andExpect(status().isOk())
			.andExpect(jsonPath("$.reason").value("empty_pantry"))
			.andExpect(jsonPath("$.suggestions.length()").value(0))
			.andExpect(jsonPath("$.disclaimer").isNotEmpty());
		assertThat(AI.requests(RETRIEVE)).isEmpty();
	}

	@Test
	void sendsOnlyUsableStockAndStoredPreferencesWithTheServiceToken() throws Exception {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
		item(this.alice, "Spinach", today.plusDays(1));
		item(this.alice, "Old milk", today.minusDays(1));
		String finished = item(this.alice, "Rice", null);
		this.mvc.perform(post("/api/v1/pantry-items/" + finished + "/events").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"finished\"}"));
		this.mvc.perform(put("/api/v1/preferences").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"diets\":[\"vegetarian\"],\"allergens\":[\"peanuts\"]}"));
		UUID recipe = recipe("ok-" + UUID.randomUUID(), new String[] { "vegetarian" }, true, new String[] {});
		AI.answer(RETRIEVE, 200, "{\"results\":[{\"recipeId\":\"" + recipe + "\",\"title\":\"Fixture\"}],"
				+ "\"excludedByPreferences\":3,\"reason\":null}");

		suggestions(this.alice).andExpect(status().isOk())
			.andExpect(jsonPath("$.suggestions[0].recipeId").value(recipe.toString()))
			.andExpect(jsonPath("$.excludedByPreferences").value(3));

		String sent = AI.requests(RETRIEVE).get(0);
		assertThat(AI.tokens.get(0)).isEqualTo(TOKEN);
		assertThat(AI.upgrades).isEmpty();
		assertThat((List<Object>) JsonPath.read(sent, "$.pantry[*].name")).containsExactly("Spinach");
		assertThat((Integer) JsonPath.read(sent, "$.pantry[0].daysToExpiry")).isEqualTo(1);
		assertThat((List<Object>) JsonPath.read(sent, "$.diets")).containsExactly("vegetarian");
		assertThat((List<Object>) JsonPath.read(sent, "$.allergens")).containsExactly("peanuts");
	}

	@Test
	void unsafeOrUnknownRecipesFromTheAiServiceAreDropped() throws Exception {
		item(this.alice, "Bread", null);
		this.mvc.perform(put("/api/v1/preferences").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"diets\":[],\"allergens\":[\"peanuts\"]}"));
		UUID safe = recipe("safe-" + UUID.randomUUID(), new String[] {}, true, new String[] {});
		UUID peanuts = recipe("nuts-" + UUID.randomUUID(), new String[] {}, true, new String[] { "peanuts" });
		UUID unknown = recipe("unk-" + UUID.randomUUID(), new String[] {}, false, new String[] {});
		AI.answer(RETRIEVE, 200, "{\"results\":[{\"recipeId\":\"" + peanuts + "\"},{\"recipeId\":\"" + safe + "\"},"
				+ "{\"recipeId\":\"" + unknown + "\"},{\"recipeId\":\"" + UUID.randomUUID() + "\"},"
				+ "{\"recipeId\":\"not-a-uuid\"}],\"excludedByPreferences\":0,\"reason\":null}");
		suggestions(this.alice).andExpect(status().isOk())
			.andExpect(jsonPath("$.suggestions.length()").value(1))
			.andExpect(jsonPath("$.suggestions[0].recipeId").value(safe.toString()));
	}

	@Test
	void aiFailuresBecomeSafeErrors() throws Exception {
		item(this.alice, "Onion", null);
		AI.answer(RETRIEVE, 500, "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"stack trace here\"}}");
		suggestions(this.alice).andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.code").value("AI_ERROR"))
			.andExpect(jsonPath("$.error.message").value("Recipe suggestions failed. Try again later."));
		AI.answer(RETRIEVE, 503, "{}");
		suggestions(this.alice).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));
		AI.delayMillis = 1500;
		AI.answer(RETRIEVE, 200, "{\"results\":[]}");
		suggestions(this.alice).andExpect(status().isGatewayTimeout())
			.andExpect(jsonPath("$.error.code").value("AI_TIMEOUT"));
	}

	@Test
	void requestValidationAndAuthentication() throws Exception {
		this.mvc.perform(get("/api/v1/recipes/suggestions")).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/v1/recipes/suggestions?limit=0").header("Authorization", this.alice))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		this.mvc.perform(get("/api/v1/recipes/suggestions?limit=500").header("Authorization", this.alice))
			.andExpect(status().isBadRequest());
	}

	@Test
	void eachUserSendsOnlyTheirOwnPantry() throws Exception {
		String bob = TestUsers.signUp(this.mvc);
		item(this.alice, "Alice only item", null);
		item(bob, "Bob only item", null);
		AI.answer(RETRIEVE, 200, "{\"results\":[],\"excludedByPreferences\":0,\"reason\":\"no_match\"}");
		suggestions(bob).andExpect(jsonPath("$.reason").value("no_match"));
		assertThat(AI.requests(RETRIEVE).get(0)).contains("Bob only item").doesNotContain("Alice only item");
	}

	private ResultActions suggestions(String token) throws Exception {
		return this.mvc.perform(get("/api/v1/recipes/suggestions").header("Authorization", token));
	}

	private String item(String token, String name, LocalDate expiry) throws Exception {
		String body = "{\"name\":\"" + name + "\",\"category\":\"other\",\"quantity\":1,\"unit\":\"piece\""
				+ (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"") + "}";
		return JsonPath.read(this.mvc.perform(post("/api/v1/pantry-items").header("Authorization", token)
			.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private UUID recipe(String key, String[] labels, boolean complete, String[] flags) {
		UUID id = this.db.sql("INSERT INTO recipes (source_name, source_key, source_license, dataset_version, title,"
				+ " diet_labels, allergen_metadata_complete) VALUES ('fixture', ?, 'test', 'v1', 'Fixture', ?, ?) RETURNING id")
			.params(key, labels, complete)
			.query(UUID.class)
			.single();
		this.db.sql("INSERT INTO recipe_ingredients (recipe_id, ingredient_name, normalized_ingredient, allergen_flags,"
				+ " sort_order) VALUES (?, 'x', 'x', ?, 0)")
			.params(id, flags)
			.update();
		return id;
	}

}
