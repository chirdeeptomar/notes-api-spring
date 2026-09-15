package com.empyrean.elide.datastore;

import io.restassured.RestAssured;
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
 * Verifies the HJSON-defined {@code noteStats} aggregation table resolves from the classpath and
 * serves tenant-scoped counts grouped by author email.
 * <p>
 * Replaces the source project's {@code AnalyticsConfigPathTest}, which tested a Quarkus
 * classloader workaround that has no equivalent here - the coverage that matters is that the
 * config resolves and the data is correctly scoped, which is asserted end to end instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsTest {

    private static final String JSON_API = "application/vnd.api+json";
    private static final String STATS_PATH = "/api/v1/noteStats";

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void noteStatsGroupsCountsByEmail() {
        String email = "stats-" + UUID.randomUUID() + "@example.com";
        createNote(null, "a note for stats", email);

        given()
                .accept(JSON_API)
                .get(STATS_PATH)
                .then()
                .statusCode(200)
                .body("data.attributes.email", hasItem(email));
    }

    /**
     * The aggregation store queries over plain JDBC and cannot use Hibernate's tenant resolver,
     * so this is what proves TenantAwareDataSource is actually applying the tenant's schema.
     */
    @Test
    void noteStatsIsScopedToTheRequestingTenant() {
        String emailA = "tenant-a-" + UUID.randomUUID() + "@example.com";
        createNote("key-a", "tenant a stats note", emailA);

        // Sanity: the note really is in tenant_a's schema (JSON:API path, known-good tenancy).
        given()
                .header("X-API-KEY", "key-a")
                .accept(JSON_API)
                .get("/api/v1/notes")
                .then()
                .statusCode(200)
                .body("data.attributes.email", hasItem(emailA));

        given()
                .header("X-API-KEY", "key-a")
                .accept(JSON_API)
                .get(STATS_PATH)
                .then()
                .statusCode(200)
                .body("data.attributes.email", hasItem(emailA));

        given()
                .header("X-API-KEY", "key-b")
                .accept(JSON_API)
                .get(STATS_PATH)
                .then()
                .statusCode(200)
                .body("data.attributes.email", not(hasItem(emailA)));
    }

    private void createNote(String apiKey, String body, String email) {
        RequestSpecification spec = given();
        if (apiKey != null) {
            spec = spec.header("X-API-KEY", apiKey);
        }
        spec.contentType(JSON_API)
                .accept(JSON_API)
                .body("""
                        {"data":{"type":"notes","attributes":{"body":"%s","email":"%s"}}}"""
                        .formatted(body, email))
                .post("/api/v1/notes")
                .then()
                .statusCode(201);
    }
}
