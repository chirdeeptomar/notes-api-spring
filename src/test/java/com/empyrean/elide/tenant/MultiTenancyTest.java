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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiTenancyTest {

    private static final String NOTES_PATH = "/api/v1/notes";
    private static final String JSON_API = "application/vnd.api+json";

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void tenantsAreFullyIsolatedFromEachOther() {
        String bodyA = "tenant a note " + UUID.randomUUID();
        String bodyB = "tenant b note " + UUID.randomUUID();
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
    void missingApiKeyIsRejected() {
        given()
                .accept(JSON_API)
                .get(NOTES_PATH)
                .then()
                .statusCode(401)
                .body("errors[0].detail", org.hamcrest.Matchers.containsString("Missing"));
    }

    @Test
    void blankApiKeyIsRejected() {
        given()
                .header("X-API-KEY", "")
                .accept(JSON_API)
                .get(NOTES_PATH)
                .then()
                .statusCode(401);
    }

    @Test
    void unknownApiKeyIsRejected() {
        given()
                .header("X-API-KEY", "not-a-real-key")
                .accept(JSON_API)
                .get(NOTES_PATH)
                .then()
                .statusCode(401)
                .body("errors[0].detail", org.hamcrest.Matchers.containsString("Unknown"));
    }

    /**
     * Servlet threads are pooled. A request that set a tenant must not leave it visible to the
     * next request served by the same thread - see TenantHeaderFilter's finally block. A
     * subsequent unkeyed request is itself rejected (there is no default tenant to leak into),
     * so the leak check is: the keyed note must not surface via the earlier tenant's key either.
     */
    @Test
    void tenantDoesNotLeakToASubsequentRequestOnTheSameThread() {
        String leaked = "leak check " + UUID.randomUUID();
        createNote("key-a", leaked, "a@example.com");

        given()
                .accept(JSON_API)
                .get(NOTES_PATH)
                .then()
                .statusCode(401);

        listNotes("key-b").body("data.attributes.body", not(hasItem(leaked)));
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
