package com.pantrychef.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pantrychef.PantryChefTest;
import com.pantrychef.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// FR-011, FR-012 (stored preferences), FR-002/QA-003 (isolation), NFR-002 (no allergy detail in logs).
@PantryChefTest
@ExtendWith(OutputCaptureExtension.class)
class PreferencesTests {

	@Autowired
	MockMvc mvc;

	String alice;

	String bob;

	@BeforeEach
	void users() throws Exception {
		this.alice = TestUsers.signUp(this.mvc);
		this.bob = TestUsers.signUp(this.mvc);
	}

	@Test
	void startEmptyThenReplaceWholeSetsDeduplicated() throws Exception {
		this.mvc.perform(get("/api/v1/preferences").header("Authorization", this.alice))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.diets.length()").value(0))
			.andExpect(jsonPath("$.allergens.length()").value(0));
		save(this.alice, "{\"diets\":[\"vegan\",\"gluten_free\",\"vegan\"],\"allergens\":[\"sesame\",\"peanuts\"]}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.diets[0]").value("gluten_free"))
			.andExpect(jsonPath("$.diets[1]").value("vegan"))
			.andExpect(jsonPath("$.diets.length()").value(2))
			.andExpect(jsonPath("$.allergens[0]").value("peanuts"));
		save(this.alice, "{\"diets\":[],\"allergens\":[\"dairy\"]}").andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/preferences").header("Authorization", this.alice))
			.andExpect(jsonPath("$.diets.length()").value(0))
			.andExpect(jsonPath("$.allergens[0]").value("dairy"))
			.andExpect(jsonPath("$.allergens.length()").value(1));
	}

	@Test
	void invalidCodesAreRejectedAndNothingChanges() throws Exception {
		save(this.alice, "{\"diets\":[\"vegetarian\"],\"allergens\":[\"eggs\"]}").andExpect(status().isOk());
		save(this.alice, "{\"diets\":[\"keto\"],\"allergens\":[]}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.fieldErrors['diets[0]']").exists());
		save(this.alice, "{\"diets\":[],\"allergens\":[\"PEANUTS\"]}").andExpect(status().isBadRequest());
		save(this.alice, "{\"diets\":[null],\"allergens\":[]}").andExpect(status().isBadRequest());
		save(this.alice, "{\"allergens\":[]}").andExpect(status().isBadRequest());
		save(this.alice, "{\"diets\":[],\"allergens\":[" + "\"dairy\",".repeat(20) + "\"eggs\"]}")
			.andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/v1/preferences").header("Authorization", this.alice))
			.andExpect(jsonPath("$.diets[0]").value("vegetarian"))
			.andExpect(jsonPath("$.allergens[0]").value("eggs"));
	}

	@Test
	void preferencesArePrivateAndNeverLogged(CapturedOutput output) throws Exception {
		save(this.alice, "{\"diets\":[\"vegan\"],\"allergens\":[\"shellfish\"]}").andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/preferences").header("Authorization", this.bob))
			.andExpect(jsonPath("$.allergens.length()").value(0));
		this.mvc.perform(get("/api/v1/preferences")).andExpect(status().isUnauthorized());
		assertThat(output.getAll()).doesNotContain("shellfish");
	}

	private ResultActions save(String token, String body) throws Exception {
		return this.mvc.perform(put("/api/v1/preferences").header("Authorization", token)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

}
