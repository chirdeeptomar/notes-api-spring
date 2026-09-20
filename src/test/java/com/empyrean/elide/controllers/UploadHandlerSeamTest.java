package com.empyrean.elide.controllers;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import com.empyrean.elide.controllers.handlers.UploadHandler;
import com.empyrean.elide.controllers.responses.UploadResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies the {@link UploadHandler} seam: adding a handler bean is enough to
 * change how an
 * upload is processed, with no change to {@link UploadController}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadHandlerSeamTest {

    @TestConfiguration
    static class WithTestHandler {
        @Bean
        UploadHandler countingTextHandler() {
            return new UploadHandler() {
                @Override
                public String name() {
                    return "counting-text";
                }

                @Override
                public boolean supports(String contentType, String fileName) {
                    return contentType != null && contentType.startsWith("text/");
                }

                @Override
                public UploadResult handle(String fileName, String contentType, long size,
                        InputStream content) throws IOException {
                    String text = new String(content.readAllBytes(), StandardCharsets.UTF_8);
                    return UploadResult.handled(fileName, contentType, size, name(),
                            Map.of("wordCount", text.isBlank() ? 0 : text.trim().split("\\s+").length));
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void registeredHandlerClaimsMatchingUpload() {
        given()
                .header("X-API-KEY", "key-a")
                .multiPart("file", "words.txt", "one two three four".getBytes(), "text/plain")
                .post("/api/v1/uploads")
                .then()
                .statusCode(200)
                .body("handler", equalTo("counting-text"))
                .body("details.wordCount", equalTo(4));
    }

    @Test
    void unmatchedContentTypeStillFallsThroughToUnhandled() {
        given()
                .header("X-API-KEY", "key-a")
                .multiPart("file", "data.bin", new byte[] { 1, 2, 3 }, "application/octet-stream")
                .post("/api/v1/uploads")
                .then()
                .statusCode(200)
                .body("handler", equalTo("none"));
    }
}
