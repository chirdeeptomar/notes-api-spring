package com.empyrean.elide.tenant;

import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Confirms {@link TenantHeaderFilter} resolves tenants for GraphQL requests the same way it
 * does for JSON:API ones: without this, every GraphQL request would silently serve the wrong
 * tenant's data and an unrecognized {@code X-API-KEY} was never rejected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GraphQLTenancyTest {

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @org.junit.jupiter.api.BeforeEach
    void setUpPort() {
        io.restassured.RestAssured.port = port;
    }

    private static final String GRAPHQL_PATH = "/graphql/api/v1";

    @Test
    void tenantsAreFullyIsolatedFromEachOther() {
        String bodyA = "graphql tenant a note " + UUID.randomUUID();
        String bodyB = "graphql tenant b note " + UUID.randomUUID();
        createNote("key-a", bodyA, "gql-a@example.com");
        createNote("key-b", bodyB, "gql-b@example.com");

        listNoteBodies("key-a")
                .body("data.notes.edges.node.body", hasItem(bodyA))
                .body("data.notes.edges.node.body", not(hasItem(bodyB)));

        listNoteBodies("key-b")
                .body("data.notes.edges.node.body", hasItem(bodyB))
                .body("data.notes.edges.node.body", not(hasItem(bodyA)));
    }

    @Test
    void unknownApiKeyIsRejected() {
        given()
                .header("X-API-KEY", "not-a-real-key")
                .contentType("application/json")
                .accept("application/json")
                .body("""
                        {"query":"{ notes { edges { node { id } } } }"}
                        """)
                .post(GRAPHQL_PATH)
                .then()
                .statusCode(401);
    }

    private void createNote(String apiKey, String body, String email) {
        withApiKey(given(), apiKey)
                .contentType("application/json")
                .accept("application/json")
                .body("""
                        {"query":"mutation { notes(op: UPSERT, data: {body: \\"%s\\", email: \\"%s\\"}) { edges { node { id } } } }"}
                        """.formatted(body, email))
                .post(GRAPHQL_PATH)
                .then()
                .statusCode(200);
    }

    private ValidatableResponse listNoteBodies(String apiKey) {
        return withApiKey(given(), apiKey)
                .contentType("application/json")
                .accept("application/json")
                .body("""
                        {"query":"{ notes { edges { node { body } } } }"}
                        """)
                .post(GRAPHQL_PATH)
                .then()
                .statusCode(200);
    }

    private RequestSpecification withApiKey(RequestSpecification spec, String apiKey) {
        return apiKey != null ? spec.header("X-API-KEY", apiKey) : spec;
    }
}
