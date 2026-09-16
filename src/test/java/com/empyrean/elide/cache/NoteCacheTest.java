package com.empyrean.elide.cache;

import com.empyrean.elide.model.Note;
import com.empyrean.elide.tenant.TenantContext;
import com.empyrean.elide.tenant.TenantInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.cache.jcache.internal.JCacheRegionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.cache.Cache;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Infinispan second-level cache serves entity reads, and - the part that actually
 * matters under schema-per-tenant - that it does so without letting one tenant observe another's
 * rows or consume another's cache region.
 * <p>
 * These assertions are deliberately made against the cache and Hibernate's statistics rather than
 * over HTTP. A cross-tenant read returning the correct answer proves only that <em>something</em>
 * isolated it, and {@code SchemaMultiTenantConnectionProvider} would provide that on its own: an
 * HTTP-level test passes even if the cache is leaking, because the schema routing underneath it
 * quietly fetches the right row. Asserting on region names, region-scoped entry counts and cache
 * hit/miss counters is what distinguishes "the cache is tenant-scoped" from "the database saved
 * us".
 *
 * @see com.empyrean.elide.config.TenantAwareJCacheRegionFactory
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class NoteCacheTest {

    private static final String NOTE_REGION = Note.class.getName();

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    TenantInfo tenantInfo;

    private SessionFactory sessionFactory;
    private Statistics statistics;
    private javax.cache.CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        statistics = sessionFactory.getStatistics();
        cacheManager = jcacheManager();
        TenantContext.clear();
        // Start from a known state: other test classes share this JVM and the in-memory database,
        // so both the cache and the counters carry their leftovers otherwise.
        sessionFactory.getCache().evictAllRegions();
        statistics.clear();
    }

    /**
     * The region-level guarantee. Each tenant must get its own Infinispan cache for the entity,
     * so that eviction pressure and sizing are per-tenant rather than shared.
     * <p>
     * Asserted against the JCache {@link javax.cache.CacheManager}, not Hibernate's statistics:
     * Hibernate only ever sees ONE logical region per entity ({@code com.empyrean.elide.model.Note}),
     * because the tenant split happens underneath it inside the region factory's storage access.
     * The physical per-tenant caches are therefore invisible to {@code getSecondLevelCacheRegionNames()}
     * and only observable here.
     */
    @Test
    void eachTenantGetsItsOwnCacheRegion() {
        for (String tenantId : allTenantIds()) {
            assertThat(cacheManager.getCache(NOTE_REGION + "." + tenantId))
                    .as("tenant %s should have its own Infinispan cache for %s", tenantId, NOTE_REGION)
                    .isNotNull();
        }
    }

    /**
     * The key-level guarantee, asserted at the cache rather than through a query: a row cached by
     * one tenant must land in that tenant's cache and nowhere else.
     * <p>
     * Note that {@code SessionFactory.getCache().containsEntity(...)} cannot be used for this.
     * That API carries no tenant, so under this region factory it cannot say which tenant's cache
     * to look in - which is precisely why the assertions below go to the physical caches instead.
     */
    @Test
    void cachedRowLandsOnlyInItsOwnTenantsCache() {
        // Only tenant_a writes and reads. Under READ_WRITE an insert populates the cache on
        // commit, so tenant_a's row is cached without a read being needed.
        UUID idA = withTenant("tenant_a", () -> persistNote("cache a " + UUID.randomUUID()));
        withTenant("tenant_a", () -> findInFreshSession(idA));

        assertThat(entryCount(NOTE_REGION + ".tenant_a"))
                .as("tenant_a wrote and read a row, so its cache should hold exactly that one")
                .isEqualTo(1);
        assertThat(entryCount(NOTE_REGION + ".tenant_b"))
                .as("tenant_b did nothing at all - a shared cache would surface tenant_a's entry here")
                .isZero();
        assertThat(entryCount(NOTE_REGION + ".public"))
                .as("the default tenant did nothing either")
                .isZero();

        // tenant_b now writes its own row: each tenant's cache holds only its own.
        UUID idB = withTenant("tenant_b", () -> persistNote("cache b " + UUID.randomUUID()));
        withTenant("tenant_b", () -> findInFreshSession(idB));

        assertThat(entryCount(NOTE_REGION + ".tenant_a")).isEqualTo(1);
        assertThat(entryCount(NOTE_REGION + ".tenant_b")).isEqualTo(1);
    }

    /**
     * Warming tenant_a's cache must not make tenant_b's read a cache hit. If the regions or keys
     * were shared, tenant_b would be served tenant_a's row from memory without touching the
     * database at all.
     */
    @Test
    void cachedNoteDoesNotLeakAcrossTenants() {
        UUID idA = withTenant("tenant_a", () -> persistNote("leak check " + UUID.randomUUID()));

        // Warm tenant_a twice: the second read should be a hit, proving the cache is live.
        withTenant("tenant_a", () -> findInFreshSession(idA));
        long hitsBefore = statistics.getSecondLevelCacheHitCount();
        withTenant("tenant_a", () -> findInFreshSession(idA));
        assertThat(statistics.getSecondLevelCacheHitCount())
                .as("tenant_a's second read should hit the cache")
                .isGreaterThan(hitsBefore);

        // The same id under tenant_b must not resolve at all - not from cache, not from its schema.
        Note fromB = withTenant("tenant_b", () -> findInFreshSession(idA));
        assertThat(fromB)
                .as("tenant_b must not see tenant_a's note, cached or otherwise")
                .isNull();
    }

    /**
     * A second read of the same id should not go to the database.
     */
    @Test
    void secondReadOfTheSameNoteHitsTheCache() {
        UUID id = withTenant("tenant_a", () -> persistNote("hit check " + UUID.randomUUID()));

        withTenant("tenant_a", () -> findInFreshSession(id));
        long hitsBefore = statistics.getSecondLevelCacheHitCount();
        long missesBefore = statistics.getSecondLevelCacheMissCount();

        Note second = withTenant("tenant_a", () -> findInFreshSession(id));

        assertThat(second).isNotNull();
        assertThat(statistics.getSecondLevelCacheHitCount()).isGreaterThan(hitsBefore);
        assertThat(statistics.getSecondLevelCacheMissCount()).isEqualTo(missesBefore);
    }

    /**
     * A write must not leave the old value readable. READ_WRITE's soft locks are what make this
     * hold; a misconfigured strategy would serve the pre-update body from cache.
     */
    @Test
    void updateEvictsStaleEntry() {
        UUID id = withTenant("tenant_a", () -> persistNote("before " + UUID.randomUUID()));
        withTenant("tenant_a", () -> findInFreshSession(id));

        String updated = "after " + UUID.randomUUID();
        withTenant("tenant_a", () -> {
            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                em.getTransaction().begin();
                em.find(Note.class, id).setBody(updated);
                em.getTransaction().commit();
            } finally {
                em.close();
            }
            return null;
        });

        Note reloaded = withTenant("tenant_a", () -> findInFreshSession(id));
        assertThat(reloaded.getBody()).isEqualTo(updated);
    }

    /**
     * Guards the session-less {@code evictData()} override in TenantAwareJCacheRegionFactory.
     * {@code evictAllRegions()} carries no tenant, so without the fan-out it would clear one
     * tenant's cache and silently leave every other tenant serving stale entries.
     */
    @Test
    void evictAllRegionsClearsEveryTenant() {
        UUID idA = withTenant("tenant_a", () -> persistNote("evict a " + UUID.randomUUID()));
        UUID idB = withTenant("tenant_b", () -> persistNote("evict b " + UUID.randomUUID()));
        withTenant("tenant_a", () -> findInFreshSession(idA));
        withTenant("tenant_b", () -> findInFreshSession(idB));

        assertThat(entryCount(NOTE_REGION + ".tenant_a")).isPositive();
        assertThat(entryCount(NOTE_REGION + ".tenant_b")).isPositive();

        sessionFactory.getCache().evictAllRegions();

        assertThat(entryCount(NOTE_REGION + ".tenant_a"))
                .as("tenant_a's region should be cleared").isZero();
        assertThat(entryCount(NOTE_REGION + ".tenant_b"))
                .as("tenant_b's region should be cleared too - this is the fan-out under test")
                .isZero();
    }

    /**
     * The query cache is unsafe under schema-per-tenant: Hibernate's QueryKey carries no tenant
     * and every tenant generates identical SQL. This pins it off so it cannot be re-enabled
     * without someone deliberately deleting this test.
     */
    @Test
    void queryCacheIsDisabled() {
        Object setting = entityManagerFactory.getProperties()
                .get("hibernate.cache.use_query_cache");
        assertThat(String.valueOf(setting))
                .as("query cache must stay off - QueryKey has no tenant identifier")
                .isEqualTo("false");
    }

    /**
     * Reaches the JCache {@link javax.cache.CacheManager} the region factory is using, so tests
     * can inspect the physical per-tenant caches. Hibernate's own statistics cannot see them -
     * see {@link #eachTenantGetsItsOwnCacheRegion()}.
     */
    private javax.cache.CacheManager jcacheManager() {
        RegionFactory regionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .requireService(RegionFactory.class);
        return ((JCacheRegionFactory) regionFactory).getCacheManager();
    }

    private List<String> allTenantIds() {
        List<String> tenants = new ArrayList<>();
        tenants.add(tenantInfo.getDefaultTenant());
        tenants.addAll(tenantInfo.getTenants());
        return tenants;
    }

    /** Counts live entries in one tenant's physical cache. */
    private long entryCount(String cacheName) {
        Cache<Object, Object> cache = cacheManager.getCache(cacheName);
        assertThat(cache).as("cache %s should exist", cacheName).isNotNull();
        long count = 0;
        for (Cache.Entry<Object, Object> ignored : cache) {
            count++;
        }
        return count;
    }

    private <T> T withTenant(String tenantId, Supplier<T> action) {
        try {
            TenantContext.set(tenantId);
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private UUID persistNote(String body) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            Note note = new Note();
            note.setBody(body);
            note.setEmail("cache@example.com");
            em.persist(note);
            em.getTransaction().commit();
            return note.getId();
        } finally {
            em.close();
        }
    }

    /**
     * Reads through a brand-new EntityManager so the persistence context (L1) is empty and the
     * read has to be answered by the second-level cache or the database - never by the session's
     * own identity map, which would make every one of these assertions vacuous.
     */
    private Note findInFreshSession(UUID id) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.find(Note.class, id);
        } finally {
            em.close();
        }
    }
}
