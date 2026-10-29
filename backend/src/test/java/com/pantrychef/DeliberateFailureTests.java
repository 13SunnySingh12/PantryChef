package com.pantrychef;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Temporary: proves CI fails when a test fails (QA-024). Removed in the next commit.
class DeliberateFailureTests {

    @Test
    void deliberateFailure() {
        assertEquals(3, 1 + 1);
    }
}
