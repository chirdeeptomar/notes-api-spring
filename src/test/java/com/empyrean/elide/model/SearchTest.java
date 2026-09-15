package com.empyrean.elide.model;

import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
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
 * Exercises full-text search on {@code Note.body} via {@code elide-datastore-search}'s
 * {@code infix}/{@code prefix} JSON:API filters (see README's Search section for the
 * n-gram-length and {@code prefix} case-sensitivity caveats these tests pin down).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchTest {

    private static final String NOTES_PATH = "/api/v1/notes";
    private static final String JSON_API = "application/vnd.api+json";

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void infixFindsNoteContainingTerm() {
        String marker = randomWord();
        String body = "a note about " + marker + " and nothing else";
        createNote(null, body);

        searchInfix(null, marker)
                .body("data.attributes.body", hasItem(body));
    }

    @Test
    void infixExcludesNonMatchingNotes() {
        String matching = randomWord();
        String nonMatching = randomWord();
        createNote(null, "contains " + matching);
        createNote(null, "contains " + nonMatching);

        searchInfix(null, matching)
                .body("data.attributes.body", hasItem("contains " + matching))
                .body("data.attributes.body", not(hasItem("contains " + nonMatching)));
    }

    @Test
    void infixRejectsTermsShorterThanMinNgram() {
        searchInfixRaw(null, "ab").statusCode(400);
    }

    @Test
    void infixRejectsTermsLongerThanMaxNgram() {
        searchInfixRaw(null, "toolongterm").statusCode(400);
    }

    @Test
    void prefixMatchesOnlyWhenCaseMatchesFieldValue() {
        String marker = randomWord().toUpperCase();
        String body = marker + " starts this note";
        createNote(null, body);

        searchPrefix(marker)
                .body("data.attributes.body", hasItem(body));

        searchPrefix(marker.toLowerCase())
                .body("data.attributes.body", not(hasItem(body)));
    }

    @Test
    void searchIsIsolatedPerTenant() {
        String marker = randomWord();
        String body = "tenant-a note with " + marker + " inside";
        createNote("key-a", body);

        searchInfix("key-a", marker)
                .body("data.attributes.body", hasItem(body));

        searchInfix(null, marker)
                .body("data.attributes.body", not(hasItem(body)));
    }

    /**
     * Proves the cross-task gap (Hibernate Search's index being partitioned by
     * {@code hibernate.search.multi_tenancy.tenant_ids}, in application.properties) is actually
     * closed: a full-text search under one tenant must never surface another tenant's notes.
     * Database-level schema isolation does not cover this, because {@code infix} results come
     * from the shared Lucene index, not a tenant-scoped SQL query. Asserting the marker IS found
     * under its own tenant (key-a) rules out this test passing merely because search is broken
     * entirely.
     */
    @Test
    void searchLeaksNothingAcrossTenants() {
        String marker = randomWord();
        String body = "tenant-a-only note about " + marker;
        createNote("key-a", body);

        searchInfix("key-a", marker)
                .body("data.attributes.body", hasItem(body));

        searchInfix("key-b", marker)
                .body("data.attributes.body", not(hasItem(body)));
    }

    /**
     * Regression guard for {@code ElideStoreConfiguration#defaultFilterDialectCustomizer}: it
     * appends {@link com.yahoo.elide.core.filter.dialect.jsonapi.DefaultFilterDialect} after the
     * autoconfigured {@code RSQLFilterDialect} rather than replacing it, specifically so that
     * RSQL-syntax filtering - {@code filter[notes]=email=='...'}, which only
     * {@code RSQLFilterDialect} parses - keeps working. Nothing else in this suite or the
     * read-only source project exercises RSQL syntax, so without this test a future change that
     * reordered the dialect list or replaced rather than appended {@code DefaultFilterDialect}
     * would go uncaught.
     * <p>
     * Confirmed to genuinely exercise the RSQL path (not just something both dialects happen to
     * accept): temporarily removing {@code .joinFilterDialect(RSQLFilterDialect...)} from the
     * autoconfigured builder made this test fail with 400
     * {@code "Invalid query parameter: filter[notes]"} - see {@code task-6-report.md} for the
     * verbatim before/after. {@link DefaultFilterDialect} has no concept of an operator embedded
     * in a type-scoped filter value, so {@code email=='...'} as the value of {@code filter[notes]}
     * is RSQL-only syntax, not something that also happens to parse under the bracket-operator
     * dialect.
     */
    @Test
    void rsqlSyntaxStillFiltersNotes() {
        String matchingEmail = "rsql-" + UUID.randomUUID() + "@example.com";
        String nonMatchingEmail = "rsql-" + UUID.randomUUID() + "@example.com";
        String matchingBody = "note for rsql smoke test " + matchingEmail;
        String nonMatchingBody = "note for rsql smoke test " + nonMatchingEmail;
        createNoteWithEmail(null, matchingBody, matchingEmail);
        createNoteWithEmail(null, nonMatchingBody, nonMatchingEmail);

        searchRsql(null, "email=='" + matchingEmail + "'")
                .body("data.attributes.email", hasItem(matchingEmail))
                .body("data.attributes.email", not(hasItem(nonMatchingEmail)));
    }

    /** A 5-character word, within elide-datastore-search's default 3-5 character n-gram bounds. */
    private String randomWord() {
        return "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
    }

    private ValidatableResponse searchInfix(String apiKey, String term) {
        return searchInfixRaw(apiKey, term).statusCode(200);
    }

    private ValidatableResponse searchInfixRaw(String apiKey, String term) {
        return withApiKey(given(), apiKey)
                .accept(JSON_API)
                .get(NOTES_PATH + "?filter[notes.body][infix]=" + term)
                .then();
    }

    private ValidatableResponse searchPrefix(String term) {
        return given()
                .accept(JSON_API)
                .get(NOTES_PATH + "?filter[notes.body][prefix]=" + term)
                .then()
                .statusCode(200);
    }

    /** RSQL-syntax type filter, e.g. {@code rsqlExpression = "email=='foo@example.com'"}. */
    private ValidatableResponse searchRsql(String apiKey, String rsqlExpression) {
        return withApiKey(given(), apiKey)
                .accept(JSON_API)
                .get(NOTES_PATH + "?filter[notes]=" + rsqlExpression)
                .then()
                .statusCode(200);
    }

    private void createNote(String apiKey, String body) {
        createNoteWithEmail(apiKey, body, "test@example.com");
    }

    private void createNoteWithEmail(String apiKey, String body, String email) {
        withApiKey(given(), apiKey)
                .contentType(JSON_API)
                .accept(JSON_API)
                .body("""
                        {"data":{"type":"notes","attributes":{"body":"%s","email":"%s"}}}
                        """.formatted(body, email))
                .post(NOTES_PATH)
                .then()
                .statusCode(201);
    }

    private RequestSpecification withApiKey(RequestSpecification spec, String apiKey) {
        return apiKey != null ? spec.header("X-API-KEY", apiKey) : spec;
    }
}
