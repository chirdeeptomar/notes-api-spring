package com.empyrean.elide.model;

import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.UUID;

import static com.yahoo.elide.test.jsonapi.JsonApiDSL.attr;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.attributes;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.datum;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.id;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.resource;
import static com.yahoo.elide.test.jsonapi.JsonApiDSL.type;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Exercises the JSON:API CRUD lifecycle for {@code notes}: create, fetch by id, update, and
 * delete, using elide-test-helpers' JsonApiDSL per Elide's testing guide
 * (https://elide.io/pages/guide/v7/14-test.html).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoteRestCrudTest {

    private static final String NOTES_PATH = "/api/v1/notes";
    private static final String JSON_API = "application/vnd.api+json";

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void fetchesCreatedNoteById() {
        String body = "note to fetch " + UUID.randomUUID();
        String id = createNote(body, "fetch@example.com");

        given()
                .header("X-API-KEY", "key-a")
                .accept(JSON_API)
                .get(NOTES_PATH + "/" + id)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(id))
                .body("data.attributes.body", equalTo(body))
                .body("data.attributes.email", equalTo("fetch@example.com"));
    }

    @Test
    void fetchingUnknownIdReturnsNotFound() {
        given()
                .header("X-API-KEY", "key-a")
                .accept(JSON_API)
                .get(NOTES_PATH + "/" + UUID.randomUUID())
                .then()
                .statusCode(404);
    }

    @Test
    void updatesNoteAttributes() {
        String id = createNote("original body", "original@example.com");

        given()
                .header("X-API-KEY", "key-a")
                .contentType(JSON_API)
                .accept(JSON_API)
                .body(datum(resource(
                        type("notes"),
                        id(id),
                        attributes(attr("body", "updated body")))).toJSON())
                .patch(NOTES_PATH + "/" + id)
                .then()
                .statusCode(204);

        given()
                .header("X-API-KEY", "key-a")
                .accept(JSON_API)
                .get(NOTES_PATH + "/" + id)
                .then()
                .statusCode(200)
                .body("data.attributes.body", equalTo("updated body"))
                .body("data.attributes.email", equalTo("original@example.com"));
    }

    @Test
    void deletesNote() {
        String id = createNote("note to delete", "delete@example.com");

        given()
                .header("X-API-KEY", "key-a")
                .delete(NOTES_PATH + "/" + id)
                .then()
                .statusCode(204);

        given()
                .header("X-API-KEY", "key-a")
                .accept(JSON_API)
                .get(NOTES_PATH + "/" + id)
                .then()
                .statusCode(404);
    }

    private String createNote(String body, String email) {
        ValidatableResponse response = given()
                .header("X-API-KEY", "key-a")
                .contentType(JSON_API)
                .accept(JSON_API)
                .body(datum(resource(
                        type("notes"),
                        attributes(
                                attr("body", body),
                                attr("email", email)))).toJSON())
                .post(NOTES_PATH)
                .then()
                .statusCode(201);

        return response.extract().path("data.id");
    }
}
