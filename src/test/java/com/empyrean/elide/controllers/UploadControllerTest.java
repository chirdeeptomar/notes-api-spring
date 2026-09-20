package com.empyrean.elide.controllers;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadControllerTest {

    private static final String UPLOADS_PATH = "/api/v1/uploads";

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void acceptsAnyFileAndReportsItUnhandledByDefault() {
        given()
                .header("X-API-KEY", "key-a")
                .multiPart("file", "notes.txt", "hello world".getBytes(), "text/plain")
                .post(UPLOADS_PATH)
                .then()
                .statusCode(200)
                .body("fileName", equalTo("notes.txt"))
                .body("handler", equalTo("none"))
                .body("size", equalTo(11));
    }

    @Test
    void rejectsRequestWithNoFilePart() {
        given()
                .header("X-API-KEY", "key-a")
                .multiPart("other", "not-the-field")
                .post(UPLOADS_PATH)
                .then()
                .statusCode(400);
    }

    @Test
    void helloEndpointIsServedAlongsideElide() {
        given()
                .header("X-API-KEY", "key-a")
                .get("/api/v1/hello")
                .then()
                .statusCode(200)
                .body(equalTo("Hello From Notes API"));
    }
}
