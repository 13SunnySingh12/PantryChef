package com.pantrychef;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** Full application against a throwaway pgvector container, with a test-only JWT signing key. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "JWT_SIGNING_SECRET=" + TestcontainersConfiguration.TEST_JWT_SECRET)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface PantryChefTest {

}
