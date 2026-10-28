package com.pantrychef.recipes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import com.jayway.jsonpath.JsonPath;
import com.pantrychef.AiStub;
import com.pantrychef.PantryChefTest;
import com.pantrychef.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// FR-013, FR-014 (backend side), FR-002 (waste-risk is owner-scoped), NFR-005 (model unavailable).
@PantryChefTest
class EstimatesTests {

	static final String CLASSIFY = "/internal/v1/cuisine/classify";

	static final String WASTE = "/internal/v1/waste/predict";

	static final AiStub AI = new AiStub();

	@DynamicPropertySource
	static void ai(DynamicPropertyRegistry registry) {
		registry.add("pantrychef.ai.base-url", AI::baseUrl);
		registry.add("pantrychef.ai.service-token", () -> "test-only-service-token-0123456789-abcdef");
	}

	@AfterAll
	static void stop() {
		AI.server.stop(0);
	}

	@Autowired
	MockMvc mvc;

	String alice;

	@BeforeEach
	void setUp() throws Exception {
		AI.reset();
		this.alice = TestUsers.signUp(this.mvc);
	}

	@Test
	void cuisineClassificationIsValidatedAndPassedThrough() throws Exception {
		AI.answer(CLASSIFY, 200, "{\"cuisine\":\"Indian\",\"status\":\"predicted\",\"alternatives\":[\"Thai\"],"
				+ "\"modelVersion\":\"v1\",\"recognizedIngredients\":3}");
		classify("{\"ingredients\":[\"ghee\",\"cumin\",\"rice\"]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.cuisine").value("Indian"));
		assertThat(AI.requests(CLASSIFY).get(0)).isEqualTo("{\"ingredients\":[\"ghee\",\"cumin\",\"rice\"]}");
		classify("{\"ingredients\":[]}").andExpect(status().isBadRequest());
		classify("{\"ingredients\":[\" \"]}").andExpect(status().isBadRequest());
		classify("{\"ingredients\":[\"" + "x".repeat(101) + "\"]}").andExpect(status().isBadRequest());
		assertThat(AI.requests(CLASSIFY)).hasSize(1);
		AI.answer(CLASSIFY, 503, "{\"error\":{\"code\":\"MODEL_UNAVAILABLE\"}}");
		classify("{\"ingredients\":[\"ghee\"]}").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));
		this.mvc.perform(post("/api/v1/cuisine/classify").contentType(MediaType.APPLICATION_JSON)
			.content("{\"ingredients\":[\"ghee\"]}")).andExpect(status().isUnauthorized());
	}

	@Test
	void wasteRiskUsesTheStoredItemOnlyAndIsOwnerScoped() throws Exception {
		LocalDate expiry = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);
		String id = JsonPath.read(this.mvc.perform(post("/api/v1/pantry-items").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Spinach\",\"category\":\"vegetables\","
					+ "\"quantity\":4,\"unit\":\"bunch\",\"expiryDate\":\"" + expiry + "\"}"))
			.andReturn().getResponse().getContentAsString(), "$.id");
		this.mvc.perform(post("/api/v1/pantry-items/" + id + "/events").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"used\",\"quantity\":1}"));
		AI.answer(WASTE, 200, "{\"status\":\"insufficient_data\",\"riskLabel\":null,\"riskScore\":null,"
				+ "\"modelVersion\":null,\"reason\":\"not trained\"}");
		this.mvc.perform(get("/api/v1/pantry-items/" + id + "/waste-risk").header("Authorization", this.alice))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("insufficient_data"))
			.andExpect(jsonPath("$.riskScore").doesNotExist());
		String sent = AI.requests(WASTE).get(0);
		assertThat((Integer) JsonPath.read(sent, "$.daysToExpiry")).isEqualTo(2);
		assertThat((Double) JsonPath.read(sent, "$.usedFraction")).isEqualTo(0.25);
		assertThat((String) JsonPath.read(sent, "$.category")).isEqualTo("vegetables");
		String bob = TestUsers.signUp(this.mvc);
		this.mvc.perform(get("/api/v1/pantry-items/" + id + "/waste-risk").header("Authorization", bob))
			.andExpect(status().isNotFound());
		assertThat(AI.requests(WASTE)).hasSize(1);
	}

	private org.springframework.test.web.servlet.ResultActions classify(String body) throws Exception {
		return this.mvc.perform(post("/api/v1/cuisine/classify").header("Authorization", this.alice)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

}
