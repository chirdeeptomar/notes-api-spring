package com.empyrean.elide.tenant;

import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Tenant isolation must hold on its own merits, not as a side effect of
 * {@code spring.jpa.open-in-view}.
 * <p>
 * With open-in-view left at its default {@code true}, the {@code EntityManager} is opened by
 * {@code OpenEntityManagerInViewInterceptor} on the servlet thread - where
 * {@link TenantHeaderFilter} has set the tenant - and the Hibernate {@code Session} carries the
 * resolved tenant identifier across the async handoff to the worker thread. Isolation then
 * passes even if nothing propagates the {@link ThreadLocal}. Turning open-in-view off (a
 * routine change Spring Boot itself nags about at startup) removes that accident: the Session
 * is opened lazily on the {@code task-*} worker, where an unpropagated
 * {@link TenantContext} reads {@code null} and {@link RequestTenantResolver} silently answers
 * {@code public} - routing every tenant's traffic into one schema with no error.
 * <p>
 * This test pins that isolation survives without the crutch.
 *
 * @see TenantAsyncConfiguration
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.open-in-view=false")
class OpenInViewDisabledIsolationTest {

    private static final String NOTES_PATH = "/api/v1/notes";
    private static final String JSON_API = "application/vnd.api+json";

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void tenantsStayIsolatedWithoutOpenInView() {
        String bodyA = "oiv-off tenant a " + UUID.randomUUID();
        String bodyB = "oiv-off tenant b " + UUID.randomUUID();

        createNote("key-a", bodyA, "a@example.com");
        createNote("key-b", bodyB, "b@example.com");

        listNotes("key-a")
                .body("data.attributes.body", hasItem(bodyA))
                .body("data.attributes.body", not(hasItem(bodyB)));

        listNotes("key-b")
                .body("data.attributes.body", hasItem(bodyB))
                .body("data.attributes.body", not(hasItem(bodyA)));
    }

    @Test
    void publicTenantStaysSeparateWithoutOpenInView() {
        String keyed = "oiv-off keyed " + UUID.randomUUID();
        createNote("key-a", keyed, "a@example.com");

        listNotes(null).body("data.attributes.body", not(hasItem(keyed)));
    }

    private ValidatableResponse listNotes(String apiKey) {
        return withApiKey(given(), apiKey)
                .accept(JSON_API)
                .get(NOTES_PATH)
                .then()
                .statusCode(200);
    }

    private void createNote(String apiKey, String body, String email) {
        withApiKey(given(), apiKey)
                .contentType(JSON_API)
                .accept(JSON_API)
                .body("""
                        {"data":{"type":"notes","attributes":{"body":"%s","email":"%s"}}}"""
                        .formatted(body, email))
                .post(NOTES_PATH)
                .then()
                .statusCode(201);
    }

    private RequestSpecification withApiKey(RequestSpecification spec, String apiKey) {
        return apiKey == null ? spec : spec.header("X-API-KEY", apiKey);
    }
}
