package com.empyrean.elide.model;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoteValidationTest {

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @org.junit.jupiter.api.BeforeEach
    void setUpPort() {
        io.restassured.RestAssured.port = port;
    }

    private static final String NOTES_PATH = "/api/v1/notes";
    private static final String JSON_API = "application/vnd.api+json";

    @Test
    void rejectsBlankBody() {
        postNote("", "test@example.com")
                .statusCode(400)
                .body("errors[0].code", equalTo("NotBlank"))
                .body("errors[0].source.pointer", equalTo("/data/attributes/body"));
    }

    @Test
    void rejectsBodyOverMaxLength() {
        String tooLong = "a".repeat(2001);

        postNote(tooLong, "test@example.com")
                .statusCode(400)
                .body("errors[0].code", equalTo("Size"))
                .body("errors[0].source.pointer", equalTo("/data/attributes/body"));
    }

    @Test
    void rejectsMalformedEmail() {
        postNote("hello", "not-an-email")
                .statusCode(400)
                .body("errors[0].code", equalTo("Email"))
                .body("errors[0].source.pointer", equalTo("/data/attributes/email"));
    }

    @Test
    void rejectsBlankEmail() {
        postNote("hello", "")
                .statusCode(400)
                .body("errors[0].source.pointer", equalTo("/data/attributes/email"));
    }

    @Test
    void acceptsValidNote() {
        postNote("a perfectly valid note", "valid@example.com")
                .statusCode(201);
    }

    private io.restassured.response.ValidatableResponse postNote(String body, String email) {
        return given()
                .contentType(JSON_API)
                .accept(JSON_API)
                .body("""
                        {"data":{"type":"notes","attributes":{"body":"%s","email":"%s"}}}
                        """.formatted(body, email))
                .post(NOTES_PATH)
                .then();
    }
}
