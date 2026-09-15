package com.empyrean.elide.hook;

import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies the sample lifecycle hooks bound to {@code Note} actually run, by asserting on
 * effects visible through the API rather than on log output.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoteLifecycleHookTest {

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @org.junit.jupiter.api.BeforeEach
    void setUpPort() {
        io.restassured.RestAssured.port = port;
    }

    private static final String NOTES_PATH = "/api/v1/notes";
    private static final String JSON_API = "application/vnd.api+json";

    @Autowired
    HookInvocationRecorder recorder;

    @BeforeEach
    void resetRecorder() {
        recorder.clear();
    }

    @Test
    void presecurityHookNormalizesWhitespaceInBodyOnCreate() {
        String id = createNote("  spaced   out  ", "normalize@example.com");

        given()
                .accept(JSON_API)
                .get(NOTES_PATH + "/" + id)
                .then()
                .statusCode(200)
                .body("data.attributes.body", equalTo("spaced out"));
    }

    @Test
    void presecurityHookNormalizesWhitespaceInBodyOnUpdate() {
        String id = createNote("original", "normalize@example.com");

        given()
                .contentType(JSON_API)
                .accept(JSON_API)
                .body("""
                        {"data":{"type":"notes","id":"%s","attributes":{"body":"  updated   text  "}}}
                        """.formatted(id))
                .patch(NOTES_PATH + "/" + id)
                .then()
                .statusCode(204);

        given()
                .accept(JSON_API)
                .get(NOTES_PATH + "/" + id)
                .then()
                .body("data.attributes.body", equalTo("updated text"));
    }

    @Test
    void precommitHookRunsBeforePostcommitHookOnCreate() {
        createNote("ordering check " + UUID.randomUUID(), "ordering@example.com");

        List<String> hookNames = recorder.getInvocations().stream()
                .map(HookInvocationRecorder.Invocation::hookName)
                .toList();

        int auditIndex = hookNames.indexOf("NoteAuditPreCommitHook");
        int postCommitIndex = hookNames.indexOf("NotePostCommitHook");

        assertTrue(auditIndex >= 0, "audit hook did not fire; recorded: " + hookNames);
        assertTrue(postCommitIndex >= 0, "post-commit hook did not fire; recorded: " + hookNames);
        assertTrue(auditIndex < postCommitIndex,
                "expected PRECOMMIT before POSTCOMMIT, recorded: " + hookNames);
    }

    @Test
    void auditHookReportsOldAndNewValueOnUpdate() {
        String id = createNote("before value", "audit@example.com");
        recorder.clear();

        given()
                .contentType(JSON_API)
                .accept(JSON_API)
                .body("""
                        {"data":{"type":"notes","id":"%s","attributes":{"body":"after value"}}}
                        """.formatted(id))
                .patch(NOTES_PATH + "/" + id)
                .then()
                .statusCode(204);

        List<String> auditDetails = recorder.getInvocations().stream()
                .filter(i -> i.hookName().equals("NoteAuditPreCommitHook"))
                .map(HookInvocationRecorder.Invocation::detail)
                .toList();

        assertTrue(
                auditDetails.stream().anyMatch(d ->
                        d.contains("body") && d.contains("before value") && d.contains("after value")),
                "no audit entry carried the old and new body; recorded: " + auditDetails);
    }

    private String createNote(String body, String email) {
        return given()
                .contentType(JSON_API)
                .accept(JSON_API)
                .body("""
                        {"data":{"type":"notes","attributes":{"body":"%s","email":"%s"}}}
                        """.formatted(body, email))
                .post(NOTES_PATH)
                .then()
                .statusCode(201)
                .extract()
                .path("data.id");
    }
}
