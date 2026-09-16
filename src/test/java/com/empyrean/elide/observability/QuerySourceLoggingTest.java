package com.empyrean.elide.observability;

import com.empyrean.elide.model.Note;
import com.empyrean.elide.tenant.TenantContext;
import io.restassured.RestAssured;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.UUID;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that the per-request tier accounting reports where data actually came from.
 * <p>
 * The point of this instrumentation is to be trustworthy when someone is diagnosing a latency
 * problem, so the tests assert that the counters <em>discriminate</em> - a cached read and an
 * uncached one must not produce the same tally. A test that only checked "some counter moved"
 * would pass against an implementation that counted every read as a database load, which is the
 * exact failure that would make the logs lie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuerySourceLoggingTest {

    @LocalServerPort
    int port;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    private SessionFactory sessionFactory;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        TenantContext.clear();
        // Other test classes share this JVM and database; start from a cold cache so a "served
        // from cache" assertion cannot pass on a leftover entry.
        sessionFactory.getCache().evictAllRegions();
        QuerySourceRecorder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        QuerySourceRecorder.clear();
    }

    /**
     * A cold read must be attributed to the database, and the second read of the same row to the
     * cache. Asserting both directions in one test is deliberate: it is the difference between
     * them that makes the log line meaningful.
     */
    @Test
    void reportsDatabaseThenCacheForTheSameRow() {
        UUID id = withTenant("tenant_a", () -> persistNote("source check " + UUID.randomUUID()));
        // The insert itself populates the cache under READ_WRITE, so evict to get a genuinely
        // cold read - otherwise the first read below would already be a hit and the test would
        // prove nothing about the database path.
        sessionFactory.getCache().evictAllRegions();

        QuerySourceRecorder cold = QuerySourceRecorder.begin();
        withTenant("tenant_a", () -> findInFreshSession(id));
        QuerySourceRecorder.clear();

        assertThat(cold.getDatabaseLoads())
                .as("a cold read must be reported as a database load")
                .isPositive();
        assertThat(cold.getCacheMisses())
                .as("and the cache lookup that preceded it as a miss")
                .isPositive();

        QuerySourceRecorder warm = QuerySourceRecorder.begin();
        withTenant("tenant_a", () -> findInFreshSession(id));
        QuerySourceRecorder.clear();

        assertThat(warm.getCacheHits())
                .as("the second read of the same row must be reported as a cache hit")
                .isPositive();
        assertThat(warm.getDatabaseLoads())
                .as("and must issue no SQL at all - otherwise the cache is not being used and "
                        + "the log line would claim a cache hit that did not save a query")
                .isZero();
    }

    /**
     * The recorder must not bleed between requests on a pooled thread. A leaked tally would
     * attribute one request's database loads to an unrelated later request.
     */
    @Test
    void recorderDoesNotOutliveItsRequest() {
        QuerySourceRecorder.begin();
        QuerySourceRecorder.record(QuerySourceRecorder.Source.DATABASE);
        QuerySourceRecorder.clear();

        assertThat(QuerySourceRecorder.current())
                .as("nothing should be recording once the request is done")
                .isNull();
    }

    /**
     * Recording outside a request must be a silent no-op, not a failure. Startup, the seeder and
     * background indexing all touch the database with no recorder installed.
     */
    @Test
    void recordingWithoutARequestIsHarmless() {
        QuerySourceRecorder.clear();
        QuerySourceRecorder.record(QuerySourceRecorder.Source.DATABASE);
        QuerySourceRecorder.recordCacheLookup(true);
        assertThat(QuerySourceRecorder.current()).isNull();
    }

    /**
     * The end-to-end guard, and the one that catches the failure this design is most exposed to.
     * Elide serves requests on an async worker thread, so a recorder that is not propagated across
     * that boundary yields an empty tally for every real API request while every unit-level test
     * above still passes. Going over HTTP is what makes that visible.
     */
    @Test
    void httpReadIsAttributedAcrossTheAsyncBoundary() {
        UUID id = withTenant("tenant_a", () -> persistNote("http check " + UUID.randomUUID()));
        sessionFactory.getCache().evictAllRegions();

        // Two identical reads over HTTP. Both must succeed; the second is the one that should be
        // served from the warmed cache.
        for (int i = 0; i < 2; i++) {
            given().header("X-API-KEY", "key-a")
                    .when().get("/api/v1/notes/" + id)
                    .then().statusCode(200)
                    .body("data.id", equalTo(id.toString()));
        }

        // The tally is asserted through the recorder rather than Hibernate's Statistics, which are
        // disabled in this context and would read zero however well the cache performed - an
        // assertion that can only fail is worse than none. The recorder is also the thing actually
        // under test: it is what the log line is built from, and it is what would silently read
        // empty if the async propagation regressed.
        QuerySourceRecorder recorder = QuerySourceLoggingFilter.lastCompleted();
        assertThat(recorder)
                .as("a recorder should have been installed and propagated to the worker thread; "
                        + "null here means the async hand-off dropped it and every API request "
                        + "would log an empty tally")
                .isNotNull();
        assertThat(recorder.getCacheHits())
                .as("the second HTTP read should have been served from the warmed cache")
                .isPositive();
        assertThat(recorder.getDatabaseLoads())
                .as("and should have issued no SQL")
                .isZero();
    }

    /**
     * The third tier. A full-text filter on {@code body} routes to Lucene via
     * {@code SearchDataStore}, and must be reported as such rather than as a database read - the
     * whole point of the log line is that these are distinguishable.
     * <p>
     * This is also the assertion that keeps {@link QuerySourceAwareSearchDataStore} honest. It
     * infers an index hit from "a filtered read that issued no SELECT" rather than reimplementing
     * Elide's private index-eligibility rule, so if a future Elide version changes how the index
     * loads matching rows, this test is what notices.
     */
    @Test
    void reportsIndexForAFullTextFilteredRead() {
        // Short random word, matching SearchTest's convention: the index is built with an ngram
        // filter bounded at 3-5 characters, so a long term matches nothing.
        String term = "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        withTenant("tenant_a", () -> persistNote("indexed " + term));

        given().header("X-API-KEY", "key-a")
                .accept("application/vnd.api+json")
                .when().get("/api/v1/notes?filter[notes.body][infix]=" + term)
                .then().statusCode(200);

        QuerySourceRecorder recorder = QuerySourceLoggingFilter.lastCompleted();
        assertThat(recorder).isNotNull();
        assertThat(recorder.getIndexQueries())
                .as("a full-text filtered read should be attributed to the Lucene index")
                .isPositive();
        // An index-served read still fetches the matching rows by id, so SQL is expected here.
        // What must not happen is the line reporting that as a plain database read - that would
        // hide the index in the logs whose whole purpose is to show it.
        assertThat(QuerySourceLoggingFilter.servedBy(recorder))
                .as("the index did the selecting, so that is what the line must say")
                .isEqualTo("index");
    }

    /**
     * The tenant on the log line must be the one that made the request.
     * <p>
     * Regression test for a bug only visible against a running server: the summary is emitted from
     * an async callback on a container thread, by which point {@code TenantContext} has been
     * cleared, so reading the tenant there reported {@code none} for every request including
     * correctly authenticated ones. Every unit-level assertion still passed, because the tally
     * itself was right - only the label was wrong, which is the part a person reads.
     */
    @Test
    void namesTheTenantThatMadeTheRequest() {
        UUID id = withTenant("tenant_a", () -> persistNote("tenant label " + UUID.randomUUID()));

        given().header("X-API-KEY", "key-a")
                .accept("application/vnd.api+json")
                .when().get("/api/v1/notes/" + id)
                .then().statusCode(200);

        // Asserted through tenantLabel - the method the log line itself calls. Checking
        // recorder.getTenantId() instead would pass even against the bug this guards, because the
        // tally was always correct; what was wrong was where the log line read the tenant from.
        assertThat(QuerySourceLoggingFilter.tenantLabel(QuerySourceLoggingFilter.lastCompleted()))
                .as("the line must name the requesting tenant, not fall back to none")
                .isEqualTo("tenant_a");
    }

    /**
     * A write serves nothing from cache, and must not claim to.
     * <p>
     * Regression test for the same live-only class of bug: {@code servedBy} ended with an
     * unguarded {@code return "cache"}, so a POST - which consults the cache, records a miss, and
     * then issues non-SELECT SQL - matched no earlier branch and was reported as cache-served.
     */
    @Test
    void doesNotClaimAWriteWasServedFromCache() {
        given().header("X-API-KEY", "key-a")
                .contentType("application/vnd.api+json")
                .accept("application/vnd.api+json")
                .body("""
                        {"data":{"type":"notes","attributes":{\
                        "body":"write label check","email":"write@example.com"}}}""")
                .when().post("/api/v1/notes")
                .then().statusCode(201);

        QuerySourceRecorder recorder = QuerySourceLoggingFilter.lastCompleted();
        assertThat(recorder.getCacheHits())
                .as("a create serves nothing from cache")
                .isZero();
        assertThat(QuerySourceLoggingFilter.servedBy(recorder))
                .as("so the line must not report it as cache-served")
                .isNotEqualTo("cache");
    }

    private UUID persistNote(String body) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            Note note = new Note();
            note.setBody(body);
            note.setEmail("observability@example.com");
            em.persist(note);
            em.getTransaction().commit();
            return note.getId();
        } finally {
            em.close();
        }
    }

    private Note findInFreshSession(UUID id) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.find(Note.class, id);
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
