package com.pantrychef;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PantrychefBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(PantrychefBackendApplication.class, args);
	}

	/** Injected wherever "now" matters so tests can pin dates and times. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
