package com.pantrychef.pantry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

// FR-002–FR-005, FR-024, NFR-010; QA-003, QA-004, QA-005, QA-006 (event part), QA-019, QA-020 (API part).
@PantryChefTest
class PantryTests {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient db;

	String alice;

	String bob;

	@BeforeEach
	void users() throws Exception {
		this.alice = TestUsers.signUp(this.mvc);
		this.bob = TestUsers.signUp(this.mvc);
	}

	@Test
	void createReadAndListOwnItems() throws Exception {
		String id = create(this.alice, item("Milk", "dairy", "2", "l", "120.50", TODAY.minusDays(1), TODAY.plusDays(2)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.quantity").value(2.0))
			.andExpect(jsonPath("$.quantityRemaining").value(2.0))
			.andExpect(jsonPath("$.currencyCode").value("INR"))
			.andExpect(jsonPath("$.expiryStatus").value("expiring_soon"))
			.andExpect(jsonPath("$.daysUntilExpiry").value(2))
			.andExpect(jsonPath("$.version").value(0))
			.andReturn().getResponse().getContentAsString();
		String itemId = JsonPath.read(id, "$.id");
		this.mvc.perform(get("/api/v1/pantry-items/" + itemId).header("Authorization", this.alice))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Milk"));
		create(this.alice, item("Rice", "grains", "1", "kg", null, null, null)).andExpect(jsonPath("$.expiryStatus").value("none"));
		create(this.alice, item("Yogurt", "dairy", "1", "piece", null, null, TODAY.minusDays(1)))
			.andExpect(jsonPath("$.expiryStatus").value("expired"));
		this.mvc.perform(get("/api/v1/pantry-items").header("Authorization", this.alice))
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].name").value("Yogurt"))
			.andExpect(jsonPath("$[2].name").value("Rice"));
	}

	@Test
	void invalidItemsAreRejectedWithFieldErrorsAndNothingIsWritten() throws Exception {
		int before = count();
		expectInvalid(item("  ", "dairy", "1", "l", null, null, null), "name");
		expectInvalid(item("Milk", "toys", "1", "l", null, null, null), "category");
		expectInvalid(item("Milk", "dairy", "0", "l", null, null, null), "quantity");
		expectInvalid(item("Milk", "dairy", "-1", "l", null, null, null), "quantity");
		expectInvalid(item("Milk", "dairy", "1.2345", "l", null, null, null), "quantity");
		expectInvalid(item("Milk", "dairy", "1", "cups", null, null, null), "unit");
		expectInvalid(item("Milk", "dairy", "1", "l", "-5", null, null), "purchasePrice");
		expectInvalid(item("Milk", "dairy", "1", "l", null, TODAY.plusDays(1), null), "purchaseDate");
		expectInvalid(item("Milk", "dairy", "1", "l", null, TODAY, TODAY.minusDays(1)), "expiryDate");
		expectInvalid(item("x".repeat(101), "dairy", "1", "l", null, null, null), "name");
		for (String body : List.of(item("Milk", "dairy", "NaN", "l", null, null, null),
				item("Milk", "dairy", "Infinity", "l", null, null, null),
				"{\"name\":\"Milk\",\"category\":\"dairy\",\"quantity\":1,\"unit\":\"l\",\"expiryDate\":\"2026-02-30\"}")) {
			create(this.alice, body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		}
		assertThat(count()).isEqualTo(before);
	}

	@Test
	void injectionAndMarkupInNamesAreStoredAsPlainData() throws Exception {
		String sql = "Milk'); DROP TABLE pantry_items;--";
		String html = "<img src=x onerror=alert(1)>";
		create(this.alice, item(sql, "dairy", "1", "l", null, null, null)).andExpect(jsonPath("$.name").value(sql));
		create(this.alice, item(html, "dairy", "1", "l", null, null, null))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.name").value(html));
		this.mvc.perform(get("/api/v1/pantry-items").header("Authorization", this.alice))
			.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void anotherUsersItemIsInvisibleAndImmutable() throws Exception {
		String id = id(create(this.alice, item("Eggs", "dairy", "12", "piece", null, null, null)));
		String path = "/api/v1/pantry-items/" + id;
		this.mvc.perform(get(path).header("Authorization", this.bob)).andExpect(status().isNotFound());
		this.mvc.perform(patch(path).header("Authorization", this.bob).contentType(MediaType.APPLICATION_JSON)
			.content(item("Mine now", "dairy", "12", "piece", null, null, null, 0L))).andExpect(status().isNotFound());
		this.mvc.perform(delete(path).header("Authorization", this.bob)).andExpect(status().isNotFound());
		event(this.bob, id, "used", "1", null).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/pantry-items").header("Authorization", this.bob))
			.andExpect(jsonPath("$.length()").value(0));
		this.mvc.perform(get(path).header("Authorization", this.alice))
			.andExpect(jsonPath("$.name").value("Eggs"))
			.andExpect(jsonPath("$.quantityRemaining").value(12.0));
	}

	@Test
	void updateNeedsCurrentVersionAndKeepsConsumedAmount() throws Exception {
		String id = id(create(this.alice, item("Flour", "grains", "2", "kg", null, null, null)));
		event(this.alice, id, "used", "0.5", null).andExpect(status().isCreated());
		String path = "/api/v1/pantry-items/" + id;
		update(path, item("Flour", "grains", "2", "kg", null, null, null, null)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.fieldErrors.version").exists());
		update(path, item("Flour", "grains", "2", "kg", null, null, null, 0L)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("STALE_UPDATE"));
		update(path, item("Wheat flour", "grains", "3", "kg", null, null, null, 1L)).andExpect(status().isOk())
			.andExpect(jsonPath("$.quantity").value(3.0))
			.andExpect(jsonPath("$.quantityRemaining").value(2.5))
			.andExpect(jsonPath("$.version").value(2));
		update(path, item("Wheat flour", "grains", "0.25", "kg", null, null, null, 2L)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("QUANTITY_BELOW_CONSUMED"));
		update(path, item("Wheat flour", "grains", "3", "g", null, null, null, 2L)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("UNIT_LOCKED"));
	}

	@Test
	void deletedItemsLeaveThePantryButKeepHistory() throws Exception {
		String id = id(create(this.alice, item("Bread", "bakery", "1", "piece", null, null, null)));
		event(this.alice, id, "discarded", "0.5", "stale").andExpect(status().isCreated());
		this.mvc.perform(delete("/api/v1/pantry-items/" + id).header("Authorization", this.alice))
			.andExpect(status().isNoContent());
		this.mvc.perform(delete("/api/v1/pantry-items/" + id).header("Authorization", this.alice))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/pantry-items/" + id).header("Authorization", this.alice))
			.andExpect(status().isNotFound());
		event(this.alice, id, "used", "0.1", null).andExpect(status().isNotFound());
		assertThat(this.db.sql("SELECT count(*) FROM pantry_item_events WHERE pantry_item_id = ?::uuid")
			.param(id).query(Integer.class).single()).isEqualTo(1);
		assertThat(this.db.sql("SELECT count(*) FROM pantry_item_events WHERE pantry_item_id = ?::uuid AND event_type = 'used'")
			.param(id).query(Integer.class).single()).isZero();
	}

	@Test
	void eventsReduceStockAndNeverOverdraw() throws Exception {
		String id = id(create(this.alice, item("Rice", "grains", "5", "kg", null, null, null)));
		event(this.alice, id, "used", "1.5", null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.event.unit").value("kg"))
			.andExpect(jsonPath("$.item.quantityRemaining").value(3.5));
		event(this.alice, id, "used", "4", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_STOCK"));
		event(this.alice, id, "discarded", "0.5", null).andExpect(jsonPath("$.item.quantityRemaining").value(3.0));
		event(this.alice, id, "finished", "1", null).andExpect(status().isBadRequest());
		event(this.alice, id, "used", null, null).andExpect(status().isBadRequest());
		event(this.alice, id, "eaten", "1", null).andExpect(status().isBadRequest());
		event(this.alice, id, "finished", null, null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.event.quantity").value(3.0))
			.andExpect(jsonPath("$.item.quantityRemaining").value(0.0));
		event(this.alice, id, "finished", null, null).andExpect(status().isConflict());
		event(this.alice, id, "used", "0.001", null).andExpect(status().isConflict());
	}

	@Test
	void repeatedIdempotencyKeyAppliesOnce() throws Exception {
		String id = id(create(this.alice, item("Oil", "condiments", "1000", "ml", null, null, null)));
		String other = id(create(this.alice, item("Ghee", "dairy", "500", "ml", null, null, null)));
		String first = keyed(id, "used", "100", "key-oil-1").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String second = keyed(id, "used", "100", "key-oil-1").andExpect(status().isOk())
			.andExpect(jsonPath("$.item.quantityRemaining").value(900.0))
			.andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(second, "$.event.id")).isEqualTo(JsonPath.read(first, "$.event.id"));
		keyed(other, "used", "100", "key-oil-1").andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
		keyed(id, "used", "200", "key-oil-1").andExpect(status().isConflict());
		String fin = keyed(id, "finished", null, "key-oil-2").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		keyed(id, "finished", null, "key-oil-2").andExpect(status().isOk())
			.andExpect(jsonPath("$.event.id").value((String) JsonPath.read(fin, "$.event.id")));
		// keys are per user: Bob may use the same key on his own item
		String bobs = id(create(this.bob, item("Oil", "condiments", "1", "l", null, null, null)));
		this.mvc.perform(post("/api/v1/pantry-items/" + bobs + "/events").header("Authorization", this.bob)
			.header("Idempotency-Key", "key-oil-1").contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"used\",\"quantity\":0.1}")).andExpect(status().isCreated());
	}

	@Test
	void concurrentEventsNeverDriveStockNegative() throws Exception {
		String id = id(create(this.alice, item("Apples", "fruits", "5", "piece", null, null, null)));
		ExecutorService pool = Executors.newFixedThreadPool(10);
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> event(this.alice, id, "used", "1", null).andReturn().getResponse().getStatus());
		}
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> f : pool.invokeAll(tasks)) {
			statuses.add(f.get());
		}
		pool.shutdown();
		assertThat(statuses).filteredOn((s) -> s == 201).hasSize(5);
		assertThat(statuses).filteredOn((s) -> s == 409).hasSize(5);
		this.mvc.perform(get("/api/v1/pantry-items/" + id).header("Authorization", this.alice))
			.andExpect(jsonPath("$.quantityRemaining").value(0.0));
	}

	@Test
	void concurrentRetriesWithOneKeyApplyOnce() throws Exception {
		String id = id(create(this.alice, item("Sugar", "condiments", "10", "kg", null, null, null)));
		ExecutorService pool = Executors.newFixedThreadPool(8);
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> keyed(id, "used", "1", "retry-key").andReturn().getResponse().getStatus());
		}
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> f : pool.invokeAll(tasks)) {
			statuses.add(f.get());
		}
		pool.shutdown();
		assertThat(statuses).filteredOn((s) -> s == 201).hasSize(1);
		assertThat(statuses).filteredOn((s) -> s == 200).hasSize(7);
		this.mvc.perform(get("/api/v1/pantry-items/" + id).header("Authorization", this.alice))
			.andExpect(jsonPath("$.quantityRemaining").value(9.0));
	}

	@Test
	void malformedIdsAndUnauthenticatedCallsAreRejected() throws Exception {
		this.mvc.perform(get("/api/v1/pantry-items/not-a-uuid").header("Authorization", this.alice))
			.andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/v1/pantry-items")).andExpect(status().isUnauthorized());
	}

	private ResultActions create(String token, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/pantry-items").header("Authorization", token)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions update(String path, String body) throws Exception {
		return this.mvc.perform(patch(path).header("Authorization", this.alice).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions event(String token, String id, String type, String quantity, String notes) throws Exception {
		return this.mvc.perform(post("/api/v1/pantry-items/" + id + "/events").header("Authorization", token)
			.contentType(MediaType.APPLICATION_JSON).content(eventBody(type, quantity, notes)));
	}

	private ResultActions keyed(String id, String type, String quantity, String key) throws Exception {
		return this.mvc.perform(post("/api/v1/pantry-items/" + id + "/events").header("Authorization", this.alice)
			.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(eventBody(type, quantity, null)));
	}

	private void expectInvalid(String body, String field) throws Exception {
		create(this.alice, body).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.fieldErrors." + field).exists());
	}

	private int count() {
		return this.db.sql("SELECT count(*) FROM pantry_items").query(Integer.class).single();
	}

	private static String id(ResultActions created) throws Exception {
		return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private static String eventBody(String type, String quantity, String notes) {
		return "{\"type\":\"" + type + "\"" + (quantity == null ? "" : ",\"quantity\":" + quantity)
				+ (notes == null ? "" : ",\"notes\":\"" + notes + "\"") + "}";
	}

	private static String item(String name, String category, String quantity, String unit, String price,
			LocalDate purchase, LocalDate expiry) {
		return item(name, category, quantity, unit, price, purchase, expiry, null);
	}

	private static String item(String name, String category, String quantity, String unit, String price,
			LocalDate purchase, LocalDate expiry, Long version) {
		return "{\"name\":\"" + name.replace("\"", "\\\"") + "\",\"category\":\"" + category + "\",\"quantity\":" + quantity
				+ ",\"unit\":\"" + unit + "\"" + (price == null ? "" : ",\"purchasePrice\":" + price)
				+ (purchase == null ? "" : ",\"purchaseDate\":\"" + purchase + "\"")
				+ (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"")
				+ (version == null ? "" : ",\"version\":" + version) + "}";
	}

}
