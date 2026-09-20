package com.empyrean.elide.observability;

import com.empyrean.elide.model.Note;
import com.empyrean.elide.tenant.TenantContext;
import io.restassured.RestAssured;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.UUID;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code svc.search.enabled=false} actually removes {@code SearchDataStore} - see
 * {@link com.empyrean.elide.config.ElideStoreConfiguration} - rather than leaving it wired with an
 * empty index.
 * <p>
 * {@code infix}/{@code prefix} keep working either way: they are operators Elide's own predicate
 * engine understands generically, not something exclusive to {@code SearchDataStore} - see
 * {@link com.empyrean.elide.config.SearchProperties#isEnabled()}. What must change is which tier
 * serves them, so this asserts through the query-source log the same way
 * {@link QuerySourceLoggingTest#reportsIndexForAFullTextFilteredRead} does for the enabled case,
 * checking the opposite outcome: a filtered read reported as database-served, not index-served.
 * <p>
 * In the {@code observability} package (rather than alongside
 * {@link com.empyrean.elide.model.SearchTest}) because it asserts through
 * {@link QuerySourceLoggingFilter#servedBy}, which is package-private.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "svc.search.enabled=false")
class SearchDisabledTest {

    @LocalServerPort
    int port;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        TenantContext.clear();
        QuerySourceRecorder.clear();
    }

    @Test
    void infixFilterFallsThroughToTheDatabaseWhenSearchIsDisabled() {
        String term = "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        withTenant("tenant_a", () -> persistNote("disabled search " + term));

        given().header("X-API-KEY", "key-a")
                .accept("application/vnd.api+json")
                .when().get("/api/v1/notes?filter[notes.body][infix]=" + term)
                .then().statusCode(200);

        QuerySourceRecorder recorder = QuerySourceLoggingFilter.lastCompleted();
        assertThat(recorder).isNotNull();
        assertThat(recorder.getIndexQueries())
                .as("with no SearchDataStore in the store list, nothing can be index-served")
                .isZero();
        assertThat(QuerySourceLoggingFilter.servedBy(recorder))
                .as("the filter must still be answered, but never by the index - whichever mix "
                        + "of database/cache tiers actually served it")
                .doesNotContain("index");
    }

    private UUID persistNote(String body) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            Note note = new Note();
            note.setBody(body);
            note.setEmail("search-disabled@example.com");
            em.persist(note);
            em.getTransaction().commit();
            return note.getId();
        } finally {
            em.close();
        }
    }

    private <T> T withTenant(String tenantId, Supplier<T> action) {
        try {
            TenantContext.set(tenantId);
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
