package com.empyrean.elide.tenant;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the tenant resolved by {@link TenantHeaderFilter} on the servlet ({@code http-nio-*})
 * thread is genuinely visible on the async worker ({@code task-*}) thread.
 * <p>
 * Elide's {@code JsonApiController} / {@code GraphqlController} methods all return
 * {@link Callable}, so Spring MVC hands the real work to {@code applicationTaskExecutor} via
 * {@code WebAsyncManager.startCallableProcessing}. The filter's {@code finally} has already
 * cleared the {@link ThreadLocal} by then. Asserting end-to-end isolation is NOT sufficient
 * proof - with {@code spring.jpa.open-in-view=true} isolation passes anyway, because the
 * Hibernate {@code Session} (and its bound tenant identifier) is opened on the servlet thread
 * and merely reused by the worker. These tests observe the worker thread directly instead.
 *
 * @see TenantAsyncConfiguration
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncTenantPropagationTest {

    /**
     * Test-only probe. Lives in {@code src/test/java} and is contributed by a
     * {@link TestConfiguration} so it never ships in the application jar. It is mapped under
     * the JSON:API prefix on purpose: that is what makes {@link TenantHeaderFilter} process it.
     */
    @RestController
    static class AsyncTenantProbeController {

        /** Records, per probe request, what the worker thread saw. */
        static final Map<String, String> LAST = new ConcurrentHashMap<>();

        @GetMapping(path = "/api/v1/__probe/async-tenant", produces = MediaType.APPLICATION_JSON_VALUE)
        Callable<String> probe() {
            String outerThread = Thread.currentThread().getName();
            String outerTenant = TenantContext.get();
            return () -> {
                String innerThread = Thread.currentThread().getName();
                String innerTenant = TenantContext.get();
                LAST.put("outerThread", outerThread);
                LAST.put("outerTenant", String.valueOf(outerTenant));
                LAST.put("innerThread", innerThread);
                LAST.put("innerTenant", String.valueOf(innerTenant));
                return """
                        {"outerThread":"%s","outerTenant":"%s","innerThread":"%s","innerTenant":"%s"}"""
                        .formatted(outerThread, outerTenant, innerThread, innerTenant);
            };
        }

        /**
         * Reports whether the worker thread that serves THIS request still carries a tenant
         * from an earlier request. The worker pool is small, so a leaked value from a previous
         * probe request would be observed here.
         */
        @GetMapping(path = "/unfiltered/__probe/worker-residue", produces = MediaType.APPLICATION_JSON_VALUE)
        Callable<String> residue() {
            return () -> """
                    {"thread":"%s","tenant":"%s"}"""
                    .formatted(Thread.currentThread().getName(), TenantContext.get());
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        AsyncTenantProbeController asyncTenantProbeController() {
            return new AsyncTenantProbeController();
        }
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        AsyncTenantProbeController.LAST.clear();
    }

    /**
     * TEST 1. The tenant must reach the async worker thread. Asserts the worker really is a
     * different thread (otherwise the test proves nothing) and that it sees the tenant that
     * the presented X-API-KEY maps to.
     */
    @Test
    void tenantIsVisibleOnTheAsyncWorkerThread() {
        Response response = RestAssured.given()
                .header("X-API-KEY", "key-a")
                .get("/api/v1/__probe/async-tenant")
                .then()
                .statusCode(200)
                .extract()
                .response();

        String outerThread = response.jsonPath().getString("outerThread");
        String outerTenant = response.jsonPath().getString("outerTenant");
        String innerThread = response.jsonPath().getString("innerThread");
        String innerTenant = response.jsonPath().getString("innerTenant");

        System.out.printf("PROBE outerThread=%s outerTenant=%s innerThread=%s innerTenant=%s%n",
                outerThread, outerTenant, innerThread, innerTenant);

        assertEquals("tenant_a", outerTenant,
                "filter must set the tenant on the servlet thread");
        assertNotEquals(outerThread, innerThread,
                "the Callable must actually run on a different thread, or this test proves nothing");
        assertNotNull(innerTenant, "tenant must not be null on the async worker thread");
        assertEquals("tenant_a", innerTenant,
                "async worker thread must see the tenant the X-API-KEY maps to");
    }

    /** There is no default/"public" tenant any more - a keyless request must be rejected. */
    @Test
    void requestWithNoKeyIsRejectedBeforeReachingTheAsyncWorker() {
        RestAssured.given()
                .get("/api/v1/__probe/async-tenant")
                .then()
                .statusCode(401);
    }

    /**
     * TEST 3. Task-executor threads are pooled exactly like servlet threads, so the worker's
     * ThreadLocal must be cleared once the Callable finishes. Drives enough keyed requests to
     * cycle the pool, then asks an UNFILTERED endpoint (the filter sets nothing for it) what
     * its worker thread currently holds - any non-null answer is a leak.
     */
    @Test
    void tenantDoesNotLeakOnPooledWorkerThreads() {
        for (int i = 0; i < 12; i++) {
            RestAssured.given()
                    .header("X-API-KEY", "key-a")
                    .get("/api/v1/__probe/async-tenant")
                    .then()
                    .statusCode(200);
        }

        for (int i = 0; i < 12; i++) {
            Response residue = RestAssured.given()
                    .get("/unfiltered/__probe/worker-residue")
                    .then()
                    .statusCode(200)
                    .extract()
                    .response();
            String thread = residue.jsonPath().getString("thread");
            // The probe formats TenantContext.get() into JSON, so an absent tenant arrives as
            // the four-character string "null" rather than a JSON null.
            String tenant = residue.jsonPath().getString("tenant");
            assertEquals("null", tenant,
                    "worker thread " + thread + " still holds tenant " + tenant
                            + " after an earlier request completed");
        }
    }

    /** The probe controller must never be part of the shipped application. */
    @Test
    void probeControllerIsTestOnly() {
        assertTrue(AsyncTenantProbeController.class.getProtectionDomain()
                        .getCodeSource().getLocation().toString().contains("test"),
                "probe controller must be loaded from test classes, not src/main");
    }
}
