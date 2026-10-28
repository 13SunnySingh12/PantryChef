package com.pantrychef.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.pantrychef.PantryChefTest;
import com.pantrychef.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// FR-019–FR-022 (A-06), FR-002/QA-003 (dashboard), QA-006 (savings side), QA-013, QA-014.
@PantryChefTest
class DashboardTests {

	static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient db;

	String alice;

	@BeforeEach
	void setUp() throws Exception {
		this.alice = TestUsers.signUp(this.mvc);
	}

	@Test
	void emptyAccountHasZeroRecordedSavingsAndNoNutrition() throws Exception {
		summary(this.alice, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.window.to").value(TODAY.toString()))
			.andExpect(jsonPath("$.window.from").value(TODAY.minusDays(29).toString()))
			.andExpect(jsonPath("$.window.timezone").value("Asia/Kolkata"))
			.andExpect(jsonPath("$.currency").value("INR"))
			.andExpect(jsonPath("$.foodSaved.events").value(0))
			.andExpect(jsonPath("$.moneySaved.totals.length()").value(0))
			.andExpect(jsonPath("$.daily.length()").value(30))
			.andExpect(jsonPath("$.nutrition").doesNotExist())
			.andExpect(jsonPath("$.note").isNotEmpty());
	}

	@Test
	void onlyUseBeforeExpiryCountsAsSavedAndPricesAreProRata() throws Exception {
		String rice = item("Rice", "2", "kg", "100", TODAY.plusDays(2));
		event(rice, "used", "0.5");                                  // saved: 500 g, 100 * 0.5 / 2 = 25.00
		String milk = item("Milk", "1", "l", null, TODAY.plusDays(1));
		event(milk, "finished", null);                               // saved, price unknown
		String old = item("Old bread", "1", "piece", "40", TODAY.minusDays(1));
		event(old, "used", "1");                                     // used after expiry: not saved
		String salt = item("Salt", "1", "kg", "20", null);
		event(salt, "used", "0.1");                                  // no expiry date: not saved
		String spinach = item("Spinach", "2", "bunch", "60", TODAY.plusDays(3));
		event(spinach, "discarded", "1");                            // thrown away: not saved
		String eggs = item("Eggs", "12", "piece", "84", TODAY.plusDays(5));
		this.mvc.perform(delete("/api/v1/pantry-items/" + eggs).header("Authorization", this.alice));  // deleted

		summary(this.alice, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.foodSaved.items").value(2))
			.andExpect(jsonPath("$.foodSaved.events").value(2))
			.andExpect(jsonPath("$.foodSaved.quantities[0].unit").value("g"))
			.andExpect(jsonPath("$.foodSaved.quantities[0].amount").value(500))
			.andExpect(jsonPath("$.foodSaved.quantities[1].unit").value("ml"))
			.andExpect(jsonPath("$.foodSaved.quantities[1].amount").value(1000))
			.andExpect(jsonPath("$.foodSaved.usedAfterExpiry").value(1))
			.andExpect(jsonPath("$.foodSaved.usedWithoutExpiryDate").value(1))
			.andExpect(jsonPath("$.thrownAway.events").value(1))
			.andExpect(jsonPath("$.thrownAway.quantities[0].unit").value("bunch"))
			.andExpect(jsonPath("$.moneySaved.totals[0].currency").value("INR"))
			.andExpect(jsonPath("$.moneySaved.totals[0].amount").value(25.00))
			.andExpect(jsonPath("$.moneySaved.eventsWithUnknownPrice").value(1))
			.andExpect(jsonPath("$.daily[29].date").value(TODAY.toString()))
			.andExpect(jsonPath("$.daily[29].savedEvents").value(2))
			.andExpect(jsonPath("$.daily[29].discardedEvents").value(1))
			.andExpect(jsonPath("$.daily[29].moneySaved").value(25.00))
			.andExpect(jsonPath("$.daily[0].savedEvents").value(0));
	}

	@Test
	void unitsAreCombinedWithinAFamilyOnly() throws Exception {
		event(item("Flour", "1", "kg", null, TODAY.plusDays(9)), "used", "0.25");
		event(item("Sugar", "500", "g", null, TODAY.plusDays(9)), "used", "100");
		event(item("Eggs", "1", "dozen", null, TODAY.plusDays(9)), "used", "1");
		summary(this.alice, "").andExpect(jsonPath("$.foodSaved.quantities.length()").value(2))
			.andExpect(jsonPath("$.foodSaved.quantities[0].unit").value("g"))
			.andExpect(jsonPath("$.foodSaved.quantities[0].amount").value(350))
			.andExpect(jsonPath("$.foodSaved.quantities[1].unit").value("piece"))
			.andExpect(jsonPath("$.foodSaved.quantities[1].amount").value(12));
	}

	@Test
	void theWindowLimitsWhichEventsCount() throws Exception {
		String rice = item("Rice", "1", "kg", "90", TODAY.plusDays(2));
		event(rice, "used", "0.5");
		this.db.sql("UPDATE pantry_item_events SET occurred_at = now() - interval '40 days' WHERE pantry_item_id = ?::uuid")
			.param(rice)
			.update();
		summary(this.alice, "").andExpect(jsonPath("$.foodSaved.events").value(0));
		summary(this.alice, "?days=60").andExpect(jsonPath("$.foodSaved.events").value(1))
			.andExpect(jsonPath("$.moneySaved.totals[0].amount").value(45.00))
			.andExpect(jsonPath("$.daily.length()").value(60));
		summary(this.alice, "?days=0").andExpect(status().isBadRequest());
		summary(this.alice, "?days=367").andExpect(status().isBadRequest());
	}

	@Test
	void nutritionCountsOneServingPerMealAndReportsMissingData() throws Exception {
		UUID full = recipe("300", "20", "30", "10");
		UUID unknown = recipe(null, null, null, null);
		String me = JsonPath.read(this.mvc.perform(get("/api/v1/auth/me").header("Authorization", this.alice))
			.andReturn().getResponse().getContentAsString(), "$.id");
		UUID plan = this.db.sql("INSERT INTO meal_plans (user_id, start_date, end_date, status)"
				+ " VALUES (?::uuid, ?, ?, 'partial') RETURNING id")
			.params(me, TODAY, TODAY.plusDays(6))
			.query(UUID.class)
			.single();
		entry(plan, full, TODAY, "breakfast");
		entry(plan, unknown, TODAY, "lunch");
		entry(plan, full, TODAY.plusDays(1), "dinner");
		entry(plan, unknown, TODAY.plusDays(2), "dinner");
		this.db.sql("INSERT INTO meal_plan_entries (meal_plan_id, plan_date, meal_period, unavailable_reason)"
				+ " VALUES (?, ?, 'dinner', 'none')").params(plan, TODAY).update();

		summary(this.alice, "").andExpect(jsonPath("$.nutrition.planId").value(plan.toString()))
			.andExpect(jsonPath("$.nutrition.plannedMeals").value(4))
			.andExpect(jsonPath("$.nutrition.mealsWithNutrition").value(2))
			.andExpect(jsonPath("$.nutrition.complete").value(false))
			.andExpect(jsonPath("$.nutrition.total.calories").value(600))
			.andExpect(jsonPath("$.nutrition.total.proteinG").value(40))
			.andExpect(jsonPath("$.nutrition.days[0].plannedMeals").value(2))
			.andExpect(jsonPath("$.nutrition.days[0].mealsWithNutrition").value(1))
			.andExpect(jsonPath("$.nutrition.days[0].total.calories").value(300))
			.andExpect(jsonPath("$.nutrition.days[2].mealsWithNutrition").value(0))
			.andExpect(jsonPath("$.nutrition.days[2].total").doesNotExist());

		String bob = TestUsers.signUp(this.mvc);
		summary(bob, "?planId=" + plan).andExpect(status().isNotFound());
		summary(bob, "").andExpect(jsonPath("$.nutrition").doesNotExist());
	}

	@Test
	void otherUsersEventsNeverCount() throws Exception {
		event(item("Rice", "1", "kg", "50", TODAY.plusDays(2)), "used", "1");
		String bob = TestUsers.signUp(this.mvc);
		summary(bob, "").andExpect(jsonPath("$.foodSaved.events").value(0))
			.andExpect(jsonPath("$.moneySaved.totals.length()").value(0));
		this.mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
	}

	private ResultActions summary(String token, String query) throws Exception {
		return this.mvc.perform(get("/api/v1/dashboard/summary" + query).header("Authorization", token));
	}

	private String item(String name, String quantity, String unit, String price, LocalDate expiry) throws Exception {
		String body = "{\"name\":\"" + name + "\",\"category\":\"other\",\"quantity\":" + quantity + ",\"unit\":\"" + unit
				+ "\"" + (price == null ? "" : ",\"purchasePrice\":" + price)
				+ (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"") + "}";
		return JsonPath.read(this.mvc.perform(post("/api/v1/pantry-items").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private void event(String id, String type, String quantity) throws Exception {
		this.mvc.perform(post("/api/v1/pantry-items/" + id + "/events").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"" + type + "\"" + (quantity == null ? "" : ",\"quantity\":" + quantity) + "}"))
			.andExpect(status().isCreated());
	}

	private UUID recipe(String calories, String protein, String carbs, String fat) {
		return this.db.sql("INSERT INTO recipes (source_name, source_key, source_license, dataset_version, title,"
				+ " calories_per_serving, protein_g_per_serving, carbs_g_per_serving, fat_g_per_serving)"
				+ " VALUES ('fixture', ?, 'test', 'v1', 'Fixture', ?::numeric, ?::numeric, ?::numeric, ?::numeric) RETURNING id")
			.params("dash-" + UUID.randomUUID(), calories, protein, carbs, fat)
			.query(UUID.class)
			.single();
	}

	private void entry(UUID plan, UUID recipe, LocalDate date, String period) {
		this.db.sql("INSERT INTO meal_plan_entries (meal_plan_id, recipe_id, plan_date, meal_period) VALUES (?, ?, ?, ?)")
			.params(plan, recipe, date, period)
			.update();
	}

}
