package com.pantrychef.plans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

// FR-015–FR-018, FR-011/012 (plans re-checked), FR-002/QA-003 (plans, grocery), QA-011, QA-012 (persistence side).
@PantryChefTest
class MealPlanTests {

	static final String TOKEN = "test-only-service-token-0123456789-abcdef";

	static final String GENERATE = "/internal/v1/meal-plans/generate";

	static final String GROCERY = "/internal/v1/grocery/compute";

	static final AiStub AI = new AiStub();

	@DynamicPropertySource
	static void ai(DynamicPropertyRegistry registry) {
		registry.add("pantrychef.ai.base-url", AI::baseUrl);
		registry.add("pantrychef.ai.service-token", () -> TOKEN);
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

	UUID safeRecipe;

	@BeforeEach
	void setUp() throws Exception {
		AI.reset();
		this.alice = TestUsers.signUp(this.mvc);
		this.safeRecipe = recipe(new String[] {});
	}

	@Test
	void storesA21SlotPlanStartingTodayWithItsGroceryList() throws Exception {
		item(this.alice, "Rice", "2", "kg");
		AI.answer(GENERATE, 200, plan(this.safeRecipe, null, "[{\"ingredient\":\"carrot\",\"quantity\":300,\"unit\":\"g\","
				+ "\"unresolved\":false},{\"ingredient\":\"salt\",\"quantity\":null,\"unit\":null,\"unresolved\":true}]"));
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
		String body = generate(this.alice, null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.startDate").value(today.toString()))
			.andExpect(jsonPath("$.endDate").value(today.plusDays(6).toString()))
			.andExpect(jsonPath("$.status").value("partial"))
			.andExpect(jsonPath("$.entries.length()").value(21))
			.andExpect(jsonPath("$.entries[0].mealPeriod").value("breakfast"))
			.andExpect(jsonPath("$.entries[0].recipeId").value(this.safeRecipe.toString()))
			.andExpect(jsonPath("$.entries[0].title").value("Fixture"))
			.andExpect(jsonPath("$.entries[1].recipeId").doesNotExist())
			.andExpect(jsonPath("$.entries[1].unavailableReason").value("Not enough recipes."))
			.andExpect(jsonPath("$.entries[20].date").value(today.plusDays(6).toString()))
			.andExpect(jsonPath("$.grocery.length()").value(2))
			.andExpect(jsonPath("$.grocery[0].ingredient").value("carrot"))
			.andExpect(jsonPath("$.grocery[1].unresolved").value(true))
			.andExpect(jsonPath("$.groceryCalculatedAt").isNotEmpty())
			.andExpect(jsonPath("$.disclaimer").isNotEmpty())
			.andReturn().getResponse().getContentAsString();
		String sent = AI.requests(GENERATE).get(0);
		assertThat((List<Object>) JsonPath.read(sent, "$.pantry[*].unit")).containsExactly("kg");
		assertThat((Integer) JsonPath.read(sent, "$.days")).isEqualTo(7);
		assertThat(AI.upgrades).isEmpty();
		assertThat(AI.requests(GROCERY)).isEmpty();
		this.mvc.perform(get("/api/v1/meal-plans/" + JsonPath.read(body, "$.id")).header("Authorization", this.alice))
			.andExpect(jsonPath("$.entries.length()").value(21));
	}

	@Test
	void unsafeProposalsBecomeExplainedGapsAndTheGroceryListIsRecomputed() throws Exception {
		this.mvc.perform(put("/api/v1/preferences").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"diets\":[],\"allergens\":[\"peanuts\"]}"));
		UUID peanuts = recipe(new String[] { "peanuts" });
		AI.answer(GENERATE, 200, plan(this.safeRecipe, peanuts, "[]"));
		AI.answer(GROCERY, 200, "{\"items\":[{\"ingredient\":\"x\",\"quantity\":1,\"unit\":\"piece\",\"unresolved\":false}]}");
		generate(this.alice, null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.entries[1].recipeId").doesNotExist())
			.andExpect(jsonPath("$.entries[1].unavailableReason").value(MealPlanService.REMOVED_BY_CHECK))
			.andExpect(jsonPath("$.grocery[0].ingredient").value("x"));
		String recomputed = AI.requests(GROCERY).get(0);
		assertThat(recomputed).contains(this.safeRecipe.toString()).doesNotContain(peanuts.toString());
	}

	@Test
	void sameIdempotencyKeyReturnsTheSamePlanAndRegenerationKeepsOldPlans() throws Exception {
		AI.answer(GENERATE, 200, plan(this.safeRecipe, null, "[]"));
		String first = JsonPath.read(generate(this.alice, "week-1").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");
		generate(this.alice, "week-1").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first));
		assertThat(AI.requests(GENERATE)).hasSize(1);
		generate(this.alice, null).andExpect(status().isCreated());
		this.mvc.perform(get("/api/v1/meal-plans").header("Authorization", this.alice))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[1].id").value(first))
			.andExpect(jsonPath("$[1].plannedSlots").value(11));
	}

	@Test
	void groceryCheckOffPersistsAndRecalculationKeepsCheckedItems() throws Exception {
		AI.answer(GENERATE, 200, plan(this.safeRecipe, null, "[{\"ingredient\":\"rice\",\"quantity\":500,\"unit\":\"g\","
				+ "\"unresolved\":false},{\"ingredient\":\"onion\",\"quantity\":2,\"unit\":\"piece\",\"unresolved\":false}]"));
		String plan = generate(this.alice, null).andReturn().getResponse().getContentAsString();
		String planId = JsonPath.read(plan, "$.id");
		String onion = JsonPath.read(plan, "$.grocery[0].id");
		check(this.alice, onion, "{\"checked\":true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.checked").value(true))
			.andExpect(jsonPath("$.checkedAt").isNotEmpty());
		check(this.alice, onion, "{}").andExpect(status().isBadRequest());

		AI.answer(GROCERY, 200, "{\"items\":[{\"ingredient\":\"onion\",\"quantity\":2,\"unit\":\"piece\",\"unresolved\":false},"
				+ "{\"ingredient\":\"tomato\",\"quantity\":3,\"unit\":\"piece\",\"unresolved\":false}],\"coveredByPantry\":[\"rice\"]}");
		this.mvc.perform(post("/api/v1/meal-plans/" + planId + "/grocery-recalculation").header("Authorization", this.alice))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.grocery.length()").value(2))
			.andExpect(jsonPath("$.grocery[0].ingredient").value("onion"))
			.andExpect(jsonPath("$.grocery[0].checked").value(true))
			.andExpect(jsonPath("$.grocery[1].ingredient").value("tomato"))
			.andExpect(jsonPath("$.grocery[1].checked").value(false));
		assertThat(AI.requests(GROCERY).get(0)).contains(this.safeRecipe.toString());

		String newOnion = JsonPath.read(this.mvc.perform(get("/api/v1/meal-plans/" + planId).header("Authorization",
				this.alice)).andReturn().getResponse().getContentAsString(), "$.grocery[0].id");
		check(this.alice, newOnion, "{\"checked\":false}").andExpect(jsonPath("$.checkedAt").doesNotExist());
	}

	@Test
	void plansAndGroceryItemsArePrivate() throws Exception {
		AI.answer(GENERATE, 200, plan(this.safeRecipe, null, "[{\"ingredient\":\"rice\",\"quantity\":1,\"unit\":\"kg\","
				+ "\"unresolved\":false}]"));
		String plan = generate(this.alice, null).andReturn().getResponse().getContentAsString();
		String planId = JsonPath.read(plan, "$.id");
		String itemId = JsonPath.read(plan, "$.grocery[0].id");
		String bob = TestUsers.signUp(this.mvc);
		this.mvc.perform(get("/api/v1/meal-plans/" + planId).header("Authorization", bob)).andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/meal-plans/" + planId + "/grocery-recalculation").header("Authorization", bob))
			.andExpect(status().isNotFound());
		check(bob, itemId, "{\"checked\":true}").andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/meal-plans").header("Authorization", bob)).andExpect(jsonPath("$.length()").value(0));
		this.mvc.perform(get("/api/v1/meal-plans")).andExpect(status().isUnauthorized());
		assertThat(this.db.sql("SELECT checked FROM grocery_items WHERE id = ?::uuid").param(itemId)
			.query(Boolean.class).single()).isFalse();
	}

	@Test
	void aiFailuresStoreNothing() throws Exception {
		AI.answer(GENERATE, 503, "{}");
		generate(this.alice, null).andExpect(status().isServiceUnavailable());
		AI.answer(GENERATE, 200, "{\"entries\":[],\"grocery\":{\"items\":[]}}");
		generate(this.alice, null).andExpect(status().isBadGateway());
		this.mvc.perform(get("/api/v1/meal-plans").header("Authorization", this.alice))
			.andExpect(jsonPath("$.length()").value(0));
	}

	/** 21 slots: odd-numbered slots unavailable; slot 1 gets {@code second} instead when given. */
	private static String plan(UUID first, UUID second, String groceryItems) {
		StringBuilder entries = new StringBuilder();
		String[] periods = { "breakfast", "lunch", "dinner" };
		for (int n = 0; n < 21; n++) {
			if (n > 0) {
				entries.append(',');
			}
			String slot = "\"day\":" + (n / 3) + ",\"mealPeriod\":\"" + periods[n % 3] + "\"";
			if (n == 1 && second != null) {
				entries.append("{").append(slot).append(",\"recipeId\":\"").append(second).append("\",\"servings\":2}");
			}
			else if (n % 2 == 0) {
				entries.append("{").append(slot).append(",\"recipeId\":\"").append(first).append("\",\"servings\":null}");
			}
			else {
				entries.append("{").append(slot).append(",\"recipeId\":null,\"reason\":\"Not enough recipes.\"}");
			}
		}
		return "{\"entries\":[" + entries + "],\"excludedByPreferences\":0,\"generationVersion\":\"planner-1\","
				+ "\"grocery\":{\"items\":" + groceryItems + ",\"coveredByPantry\":[]}}";
	}

	@Test
	void aChosenStartDateMovesEverySlotAndUsesThePantryAsOfThatDay() throws Exception {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
		LocalDate start = today.plusDays(3);
		datedItem("Milk", today.plusDays(1));   // gone by the start date
		datedItem("Spinach", today.plusDays(5)); // 2 days left on the start date
		AI.answer(GENERATE, 200, plan(this.safeRecipe, null, "[]"));
		String body = this.mvc.perform(post("/api/v1/meal-plans").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"startDate\":\"" + start + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.startDate").value(start.toString()))
			.andExpect(jsonPath("$.endDate").value(start.plusDays(6).toString()))
			.andExpect(jsonPath("$.entries.length()").value(21))
			.andExpect(jsonPath("$.entries[0].date").value(start.toString()))
			.andExpect(jsonPath("$.entries[20].date").value(start.plusDays(6).toString()))
			.andReturn().getResponse().getContentAsString();
		String sent = AI.requests(GENERATE).get(0);
		assertThat((List<Object>) JsonPath.read(sent, "$.pantry[*].name")).containsExactly("Spinach");
		assertThat((List<Object>) JsonPath.read(sent, "$.pantry[*].daysToExpiry")).containsExactly(2);
		this.mvc.perform(get("/api/v1/meal-plans").header("Authorization", this.alice))
			.andExpect(jsonPath("$[0].startDate").value(start.toString()));
		AI.answer(GROCERY, 200, "{\"items\":[]}");
		this.mvc.perform(post("/api/v1/meal-plans/" + JsonPath.read(body, "$.id") + "/grocery-recalculation")
			.header("Authorization", this.alice)).andExpect(status().isOk());
		assertThat((List<Object>) JsonPath.read(AI.requests(GROCERY).get(0), "$.pantry[*].name")).containsExactly("Spinach");

		for (String bad : new String[] { today.minusDays(1).toString(), today.plusDays(8).toString() }) {
			this.mvc.perform(post("/api/v1/meal-plans").header("Authorization", this.alice)
				.contentType(MediaType.APPLICATION_JSON).content("{\"startDate\":\"" + bad + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fieldErrors.startDate").exists());
		}
		this.mvc.perform(post("/api/v1/meal-plans").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"startDate\":\"2026-02-30\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		this.mvc.perform(post("/api/v1/meal-plans").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"startDate\":\"" + today.plusDays(7) + "\"}"))
			.andExpect(status().isCreated());
		assertThat(AI.requests(GENERATE)).hasSize(2); // invalid dates never reached the planner
	}

	private void datedItem(String name, LocalDate expiry) throws Exception {
		this.mvc.perform(post("/api/v1/pantry-items").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\",\"category\":\"other\",\"quantity\":1,\"unit\":\"piece\",\"expiryDate\":\""
					+ expiry + "\"}"))
			.andExpect(status().isCreated());
	}

	private ResultActions generate(String token, String key) throws Exception {
		var request = post("/api/v1/meal-plans").header("Authorization", token);
		return this.mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
	}

	private ResultActions check(String token, String itemId, String body) throws Exception {
		return this.mvc.perform(patch("/api/v1/grocery-items/" + itemId).header("Authorization", token)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void item(String token, String name, String quantity, String unit) throws Exception {
		this.mvc.perform(post("/api/v1/pantry-items").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\",\"category\":\"other\",\"quantity\":" + quantity + ",\"unit\":\"" + unit
					+ "\"}"));
	}

	private UUID recipe(String[] flags) {
		UUID id = this.db.sql("INSERT INTO recipes (source_name, source_key, source_license, dataset_version, title,"
				+ " allergen_metadata_complete, course) VALUES ('fixture', ?, 'test', 'v1', 'Fixture', true, 'main') RETURNING id")
			.param("plan-" + UUID.randomUUID())
			.query(UUID.class)
			.single();
		this.db.sql("INSERT INTO recipe_ingredients (recipe_id, ingredient_name, normalized_ingredient, allergen_flags,"
				+ " sort_order) VALUES (?, 'x', 'x', ?, 0)")
			.params(id, flags)
			.update();
		return id;
	}

}
