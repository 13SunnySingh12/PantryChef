package com.pantrychef.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.jayway.jsonpath.JsonPath;
import com.pantrychef.PantryChefTest;
import com.pantrychef.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

// FR-006, FR-007, FR-008, FR-023; QA-007, QA-008, QA-003 (alerts). Scans use a fixed, far-future "today" so
// the real date never matters; item changes reconcile against the real today.
@PantryChefTest
class ExpiryAlertTests {

	private static final LocalDate DAY = LocalDate.of(2031, 3, 10);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient db;

	@Autowired
	ExpiryAlerts alerts;

	String alice;

	String bob;

	@BeforeEach
	void users() throws Exception {
		this.alice = TestUsers.signUp(this.mvc);
		this.bob = TestUsers.signUp(this.mvc);
	}

	@Test
	void dailyScanAppliesTheWindowAndIsIdempotent() throws Exception {
		String expired = create(this.alice, "Yesterday", DAY.minusDays(1));
		String today = create(this.alice, "Today", DAY);
		String edge = create(this.alice, "In three days", DAY.plusDays(3));
		String later = create(this.alice, "In four days", DAY.plusDays(4));
		String none = create(this.alice, "No expiry", null);
		String empty = create(this.alice, "Finished", DAY);
		finish(empty);
		String gone = create(this.alice, "Deleted", DAY);
		this.mvc.perform(delete("/api/v1/pantry-items/" + gone).header("Authorization", this.alice));

		this.alerts.scan(DAY);
		this.alerts.scan(DAY);
		this.alerts.scan(DAY);

		assertThat(openAlerts(List.of(expired, today, edge, later, none, empty, gone)))
			.containsExactlyInAnyOrderEntriesOf(Map.of(expired, "expired", today, "expiring_soon", edge, "expiring_soon"));
		assertThat(allAlertRows(expired)).isEqualTo(1);
	}

	@Test
	void alertMovesFromSoonToExpiredKeepingHistory() throws Exception {
		String milk = create(this.alice, "Milk", DAY.plusDays(1));
		this.alerts.scan(DAY);
		assertThat(openAlerts(List.of(milk))).containsEntry(milk, "expiring_soon");
		this.alerts.scan(DAY.plusDays(2));
		assertThat(openAlerts(List.of(milk))).containsEntry(milk, "expired");
		assertThat(allAlertRows(milk)).isEqualTo(2);
		assertThat(this.db.sql("SELECT alert_date FROM expiry_alerts WHERE pantry_item_id = ?::uuid AND resolved_at IS NULL")
			.param(milk).query(LocalDate.class).single()).isEqualTo(DAY.plusDays(2));
	}

	@Test
	void parallelScansNeverDuplicateAlerts() throws Exception {
		String cheese = create(this.alice, "Cheese", DAY.plusDays(2));
		CompletableFuture<?>[] runs = new CompletableFuture<?>[6];
		for (int i = 0; i < runs.length; i++) {
			runs[i] = CompletableFuture.runAsync(() -> this.alerts.scan(DAY));
		}
		CompletableFuture.allOf(runs).join();
		assertThat(allAlertRows(cheese)).isEqualTo(1);
	}

	@Test
	void pantryChangesReconcileAlertsImmediately() throws Exception {
		LocalDate realToday = this.alerts.today();
		String spinach = create(this.alice, "Spinach", realToday.plusDays(1));
		this.mvc.perform(get("/api/v1/expiry-alerts").header("Authorization", this.alice))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].type").value("expiring_soon"))
			.andExpect(jsonPath("$[0].item.name").value("Spinach"))
			.andExpect(jsonPath("$[0].item.daysUntilExpiry").value(1));

		// moving the date out of the window resolves the alert; moving it back opens a new one
		update(spinach, "Spinach", realToday.plusDays(10), 0);
		this.mvc.perform(get("/api/v1/expiry-alerts").header("Authorization", this.alice))
			.andExpect(jsonPath("$.length()").value(0));
		update(spinach, "Spinach", realToday.minusDays(1), 1);
		this.mvc.perform(get("/api/v1/expiry-alerts").header("Authorization", this.alice))
			.andExpect(jsonPath("$[0].type").value("expired"));

		// partial use keeps the alert, using it up resolves it; expiry alone never changes stock
		this.mvc.perform(post("/api/v1/pantry-items/" + spinach + "/events").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"used\",\"quantity\":1}"))
			.andExpect(status().isCreated());
		this.mvc.perform(get("/api/v1/expiry-alerts").header("Authorization", this.alice))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].item.quantityRemaining").value(1.0));
		finish(spinach);
		this.mvc.perform(get("/api/v1/expiry-alerts").header("Authorization", this.alice))
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void alertsAreSortedByUrgencyAndPrivate() throws Exception {
		LocalDate realToday = this.alerts.today();
		create(this.alice, "Later", realToday.plusDays(3));
		create(this.alice, "Overdue", realToday.minusDays(2));
		create(this.alice, "Today", realToday);
		create(this.alice, "Fine", realToday.plusDays(9));
		this.mvc.perform(get("/api/v1/expiry-alerts").header("Authorization", this.alice))
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].item.name").value("Overdue"))
			.andExpect(jsonPath("$[0].type").value("expired"))
			.andExpect(jsonPath("$[1].item.name").value("Today"))
			.andExpect(jsonPath("$[2].item.name").value("Later"));
		this.mvc.perform(get("/api/v1/expiry-alerts").header("Authorization", this.bob))
			.andExpect(jsonPath("$.length()").value(0));
		this.mvc.perform(get("/api/v1/expiry-alerts")).andExpect(status().isUnauthorized());
	}

	private String create(String token, String name, LocalDate expiry) throws Exception {
		String body = "{\"name\":\"" + name + "\",\"category\":\"other\",\"quantity\":2,\"unit\":\"piece\""
				+ (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"") + "}";
		String json = this.mvc.perform(post("/api/v1/pantry-items").header("Authorization", token)
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.id");
	}

	private void update(String id, String name, LocalDate expiry, long version) throws Exception {
		this.mvc.perform(patch("/api/v1/pantry-items/" + id).header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"category\":\"other\","
					+ "\"quantity\":2,\"unit\":\"piece\",\"expiryDate\":\"" + expiry + "\",\"version\":" + version + "}"))
			.andExpect(status().isOk());
	}

	private void finish(String id) throws Exception {
		this.mvc.perform(post("/api/v1/pantry-items/" + id + "/events").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"finished\"}")).andExpect(status().isCreated());
	}

	private Map<String, String> openAlerts(List<String> itemIds) {
		return this.db
			.sql("SELECT pantry_item_id, alert_type FROM expiry_alerts WHERE resolved_at IS NULL"
					+ " AND pantry_item_id = ANY (?::uuid[])")
			.param(itemIds.toArray(String[]::new))
			.query((rs, n) -> Map.entry(rs.getObject(1, UUID.class).toString(), rs.getString(2)))
			.list()
			.stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private int allAlertRows(String itemId) {
		return this.db.sql("SELECT count(*) FROM expiry_alerts WHERE pantry_item_id = ?::uuid")
			.param(itemId)
			.query(Integer.class)
			.single();
	}

}
