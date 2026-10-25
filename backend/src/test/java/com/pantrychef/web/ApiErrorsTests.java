package com.pantrychef.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;

class ApiErrorsTests {

	@Test
	void unexpectedErrorsHideTheExceptionMessage() {
		var response = new ApiErrors().unexpected(new IllegalStateException("SELECT * FROM users -- secret detail"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
		assertThat(response.getBody().toString()).doesNotContain("secret detail").doesNotContain("SELECT");
	}

}
