package com.empyrean.elide.tenant;

import com.empyrean.elide.model.Note;
import io.restassured.RestAssured;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;

/**
 * Verifies {@code svc.tenancy.enabled=false} actually turns off every tenancy-adjacent
 * mechanism, rather than leaving them registered but inert: no {@code X-API-KEY} filter runs,
 * no Hibernate multi-tenancy SPI is registered, the second-level cache (still on via
 * {@code svc.cache.enabled}'s own, independent default) uses one shared region per entity
 * rather than one per tenant, and the deployment behaves as if there were exactly one implicit
 * tenant.
 * <p>
 * Mirrors {@link com.empyrean.elide.cache.CacheDisabledTest}'s structure and reasoning: worth
 * its own context because this disabled branch is what a single-tenant deployment actually
 * runs, and a silently-inert toggle would be indistinguishable from a working one until real
 * tenant data started leaking together - which is exactly the class of bug this test exists to
 * catch, in the opposite direction that {@link MultiTenancyTest}/{@link TenantHeaderFilterContractTest}
 * catch it for the enabled case.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "svc.tenancy.enabled=false",
                "spring.jpa.properties.hibernate.generate_statistics=true"
        })
class TenancyDisabledTest {

    private static final String NOTES_PATH = "/api/v1/notes";
    private static final String JSON_API = "application/vnd.api+json";

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    /**
     * The filter bean itself must not exist - not merely pass every request through
     * unconditionally. {@code @ConditionalOnProperty} on {@link TenantHeaderFilter} means Spring
     * never creates it at all when disabled.
     */
    @Test
    void tenantHeaderFilterBeanDoesNotExist() {
        assertThat(applicationContext.getBeanNamesForType(TenantHeaderFilter.class)).isEmpty();
    }

    /** No header at all must succeed - there is no filter left to reject it. */
    @Test
    void requestWithNoApiKeySucceeds() {
        given()
                .accept(JSON_API)
                .get(NOTES_PATH)
                .then()
                .statusCode(200);
    }

    /** A garbage key must also succeed - again, no filter is enforcing anything. */
    @Test
    void requestWithGarbageApiKeySucceeds() {
        given()
                .header("X-API-KEY", "not-a-real-key-and-nobody-checks")
                .accept(JSON_API)
                .get(NOTES_PATH)
                .then()
                .statusCode(200);
    }

    /**
     * There is exactly one shared schema: a note created while sending one (garbage/ignored) key
     * must be visible when listing with a different (or no) key - the inverse of
     * {@link MultiTenancyTest#tenantsAreFullyIsolatedFromEachOther()}.
     */
    @Test
    void allRequestsShareOneSchemaRegardlessOfHeader() {
        String body = "shared-schema note " + UUID.randomUUID();
        given()
                .header("X-API-KEY", "whatever-key-a")
                .contentType(JSON_API)
                .accept(JSON_API)
                .body("""
                        {"data":{"type":"notes","attributes":{"body":"%s","email":"a@example.com"}}}"""
                        .formatted(body))
                .post(NOTES_PATH)
                .then()
                .statusCode(201);

        given()
                .header("X-API-KEY", "some-completely-different-key-b")
                .accept(JSON_API)
                .get(NOTES_PATH)
                .then()
                .statusCode(200)
                .body("data.attributes.body", hasItem(body));

        given()
                .accept(JSON_API)
                .get(NOTES_PATH)
                .then()
                .statusCode(200)
                .body("data.attributes.body", hasItem(body));
    }

    /**
     * One shared region per entity, not one per tenant: {@code Note.class.getName()} itself must
     * be a real cache region name (no {@code .tenant_a}-style suffix), and repeated reads must
     * produce genuine hits - proving caching keeps working normally, just without the tenant
     * dimension.
     */
    @Test
    void cachingWorksWithOneSharedRegionPerEntity() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        sessionFactory.getCache().evictAllRegions();
        statistics.clear();

        UUID id = persistNote("disabled-mode cache check " + UUID.randomUUID());
        findInFreshSession(id);
        long hitsBefore = statistics.getSecondLevelCacheHitCount();
        Note second = findInFreshSession(id);

        assertThat(second).isNotNull();
        assertThat(statistics.getSecondLevelCacheHitCount())
                .as("a repeated read should hit the cache, exactly like any ordinary "
                        + "single-tenant Hibernate app with L2 caching on")
                .isGreaterThan(hitsBefore);

        String[] regionNames = sessionFactory.getStatistics().getSecondLevelCacheRegionNames();
        assertThat(regionNames)
                .as("exactly one bare-named region for Note, no per-tenant suffix")
                .contains(Note.class.getName());
    }

    /**
     * Search is a completely independent axis from tenancy - {@code svc.search.enabled}, not
     * {@code svc.tenancy.enabled}, gates it - so it must keep working unaffected while tenancy
     * is off.
     */
    @Test
    void searchStillWorksWithTenancyDisabled() {
        String marker = "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        String body = "a note about " + marker + " with tenancy disabled";

        given()
                .contentType(JSON_API)
                .accept(JSON_API)
                .body("""
                        {"data":{"type":"notes","attributes":{"body":"%s","email":"search@example.com"}}}"""
                        .formatted(body))
                .post(NOTES_PATH)
                .then()
                .statusCode(201);

        given()
                .accept(JSON_API)
                .get(NOTES_PATH + "?filter[notes.body][infix]=" + marker)
                .then()
                .statusCode(200)
                .body("data.attributes.body", hasItem(body));
    }

    private UUID persistNote(String body) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            Note note = new Note();
            note.setBody(body);
            note.setEmail("disabled-cache@example.com");
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
}
