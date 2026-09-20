package com.empyrean.elide.openapi;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Covers the OpenAPI documents, so that removing springdoc fails a test rather than silently
 * un-documenting every hand-written controller.
 * <p>
 * There are two documents and they are deliberately different. Elide serves {@code /api-docs}
 * itself, containing only its generated entity paths, relative to a server URL of
 * {@code /api/v1}. springdoc serves {@code /v3/api-docs}, which is the one that carries the
 * hand-written {@code @RestController}s, so a new resource is documented just by existing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocsTest {

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void elideDocumentContainsGeneratedEntityPaths() {
        given()
                .get("/api-docs")
                .then()
                .statusCode(200)
                .body("paths.'/notes'", notNullValue())
                .body("paths.'/noteStats'", notNullValue());
    }

    /** Fails if springdoc is removed from the build. */
    @Test
    void springdocDocumentContainsHandWrittenControllers() {
        given()
                .get("/v3/api-docs")
                .then()
                .statusCode(200)
                .body("paths.'/api/v1/hello'", notNullValue())
                .body("paths.'/api/v1/uploads'", notNullValue());
    }

    /**
     * The explorer UI is only as good as the document it loads. Elide's {@code /api-docs} holds
     * only its own entity paths, so a Swagger UI pointed there silently omits every hand-written
     * controller - which is exactly what happened before this was caught.
     */
    @Test
    void swaggerUiLoadsTheDocumentThatContainsEverything() {
        given()
                .get("/swagger/swagger-initializer.js")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.containsString("/v3/api-docs"));
    }

    @Test
    void springdocDocumentAlsoContainsElideEntityPaths() {
        given()
                .get("/v3/api-docs")
                .then()
                .statusCode(200)
                .body("paths.'/api/v1/notes'", notNullValue());
    }
}
