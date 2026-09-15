package com.empyrean.elide.model;

import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Exercises the GraphQL CRUD lifecycle for {@code notes}: create, fetch by id,
 * update, and
 * delete via the {@code notes} root field's {@code op} argument
 * ({@code UPSERT}/{@code DELETE}), mirroring {@link NoteRestCrudTest}'s
 * coverage of the same
 * lifecycle over JSON:API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoteGraphQLCrudTest {

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @org.junit.jupiter.api.BeforeEach
    void setUpPort() {
        io.restassured.RestAssured.port = port;
    }

        private static final String GRAPHQL_PATH = "/graphql/api/v1";
        private static final String JSON = "application/json";

        @Test
        void fetchesCreatedNoteById() {
                String body = "note to fetch " + UUID.randomUUID();
                String id = createNote(body, "fetch@example.com");

                fetchById(id)
                                .body("data.notes.edges[0].node.id", equalTo(id))
                                .body("data.notes.edges[0].node.body", equalTo(body))
                                .body("data.notes.edges[0].node.email", equalTo("fetch@example.com"));
        }

        @Test
        void fetchingUnknownIdReturnsError() {
                fetchByIdRaw(UUID.randomUUID().toString())
                                .statusCode(200)
                                .body("data.notes", nullValue())
                                .body("errors[0].message", org.hamcrest.Matchers.containsString("Unknown identifier"));
        }

        @Test
        void updatesNoteAttributes() {
                String id = createNote("original body", "original@example.com");

                given()
                                .contentType(JSON)
                                .accept(JSON)
                                .body("""
                                                {"query":"mutation { notes(op: UPSERT, data: {id: \\"%s\\", body: \\"updated body\\"}) { edges { node { id } } } }"}
                                                """
                                                .formatted(id))
                                .post(GRAPHQL_PATH)
                                .then()
                                .statusCode(200)
                                .body("errors", nullValue());

                fetchById(id)
                                .body("data.notes.edges[0].node.body", equalTo("updated body"))
                                .body("data.notes.edges[0].node.email", equalTo("original@example.com"));
        }

        @Test
        void deletesNote() {
                String id = createNote("note to delete", "delete@example.com");

                given()
                                .contentType(JSON)
                                .accept(JSON)
                                .body("""
                                                {"query":"mutation { notes(op: DELETE, ids: [\\"%s\\"]) { edges { node { id } } } }"}
                                                """
                                                .formatted(id))
                                .post(GRAPHQL_PATH)
                                .then()
                                .statusCode(200)
                                .body("errors", nullValue())
                                .body("data.notes.edges", emptyIterable());

                fetchByIdRaw(id)
                                .statusCode(200)
                                .body("data.notes", nullValue());
        }

        private String createNote(String body, String email) {
                ValidatableResponse response = given()
                                .contentType(JSON)
                                .accept(JSON)
                                .body("""
                                                {"query":"mutation { notes(op: UPSERT, data: {body: \\"%s\\", email: \\"%s\\"}) { edges { node { id } } } }"}
                                                """
                                                .formatted(body, email))
                                .post(GRAPHQL_PATH)
                                .then()
                                .statusCode(200)
                                .body("errors", nullValue());

                return response.extract().path("data.notes.edges[0].node.id");
        }

        private ValidatableResponse fetchById(String id) {
                return fetchByIdRaw(id)
                                .statusCode(200)
                                .body("errors", nullValue());
        }

        private ValidatableResponse fetchByIdRaw(String id) {
                return given()
                                .contentType(JSON)
                                .accept(JSON)
                                .body("""
                                                {"query":"{ notes(ids: [\\"%s\\"]) { edges { node { id body email } } } }"}
                                                """
                                                .formatted(id))
                                .post(GRAPHQL_PATH)
                                .then();
        }
}
