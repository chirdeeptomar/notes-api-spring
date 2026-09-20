package com.empyrean.elide.tenant;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins {@link TenantHeaderFilter}'s externally observable contract: which paths it governs, and
 * the exact 401 payloads it emits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantHeaderFilterContractTest {

    private static final String JSON_API = "application/vnd.api+json";
    private static final String BOGUS_KEY = "not-a-real-key";

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    /**
     * Segment-aware prefix matching. {@code /api/v1extra} is NOT under {@code /api/v1}; a
     * {@code startsWith} match would 401 it for an unknown key, breaking the contract that
     * non-API paths pass through untouched. These paths have no handler, so the expected
     * outcome is whatever the dispatcher does with them (404) - the point is that it is
     * emphatically not 401.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1extra",
            "/api/v1notes",
            "/graphql/api/v1x",
            "/api/v1extra/deeper"
    })
    void nonApiPathsAreNotClaimedByThePrefixMatch(String path) {
        int status = given()
                .header("X-API-KEY", BOGUS_KEY)
                .get(path)
                .then()
                .extract()
                .statusCode();

        assertNotEquals(401, status,
                path + " must pass through the tenant filter untouched, but was rejected as an "
                        + "API path - the prefix match is not segment-aware");
    }

    /** The real API path with the same bogus key must still be rejected. */
    @Test
    void theActualApiPathIsStillGuarded() {
        given().header("X-API-KEY", BOGUS_KEY).get("/api/v1/notes").then().statusCode(401);
    }

    /** The bare configured prefix itself counts as an API path. */
    @Test
    void theBarePrefixIsGuarded() {
        given().header("X-API-KEY", BOGUS_KEY).get("/api/v1").then().statusCode(401);
    }

    @Test
    void jsonApiRejectionBodyMatchesTheJsonApiErrorContract() {
        given()
                .header("X-API-KEY", BOGUS_KEY)
                .accept(JSON_API)
                .get("/api/v1/notes")
                .then()
                .statusCode(401)
                .contentType(JSON_API)
                .body("errors[0].status", equalTo("401"))
                .body("errors[0].title", equalTo("Unauthorized"))
                .body("errors[0].detail", equalTo("Unknown X-API-KEY"));
    }

    @Test
    void graphqlRejectionBodyMatchesTheGraphqlErrorContract() {
        given()
                .header("X-API-KEY", BOGUS_KEY)
                .contentType("application/json")
                .body("{\"query\":\"{ note { edges { node { id } } } }\"}")
                .post("/graphql/api/v1")
                .then()
                .statusCode(401)
                .contentType("application/json")
                .body("errors[0].message", equalTo("Unknown X-API-KEY"));
    }

    /**
     * There is no default/"public" tenant any more - a request with no key at all must be
     * rejected exactly like one with an unrecognised key, not silently served from some
     * implicit default schema.
     */
    @Test
    void missingApiKeyIsRejectedWithTheMissingKeyContract() {
        given()
                .accept(JSON_API)
                .get("/api/v1/notes")
                .then()
                .statusCode(401)
                .contentType(JSON_API)
                .body("errors[0].status", equalTo("401"))
                .body("errors[0].title", equalTo("Unauthorized"))
                .body("errors[0].detail", equalTo("Missing X-API-KEY"));
    }

    @Test
    void blankApiKeyIsRejectedWithTheMissingKeyContract() {
        given()
                .header("X-API-KEY", "")
                .accept(JSON_API)
                .get("/api/v1/notes")
                .then()
                .statusCode(401)
                .body("errors[0].detail", equalTo("Missing X-API-KEY"));
    }
}
