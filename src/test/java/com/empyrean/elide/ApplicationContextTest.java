package com.empyrean.elide;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the Spring context starts and Elide's autoconfiguration wires up cleanly.
 * This is the smoke test every other test implicitly depends on.
 */
@SpringBootTest
class ApplicationContextTest {

    @Test
    void contextLoads() {
    }
}
