package com.empyrean.elide.cache;

import com.empyrean.elide.model.Note;
import org.hibernate.cache.jcache.internal.JCacheRegionFactory;
import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the actual point of {@link com.empyrean.elide.config.TenantAwareJCacheRegionFactory}'s
 * programmatic cache creation: a tenant that exists only in {@code svc.tenant.*} - with no
 * matching {@code <invalidation-cache>} declared in {@code infinispan.xml} - still gets a real,
 * correctly bounded Infinispan cache region at boot.
 * <p>
 * {@code tenant_c} is added here via {@link DynamicPropertySource} rather than by editing
 * {@code application.properties}, so the base config committed to the repo is untouched and this
 * test is the one place the "no XML edit needed" claim is actually exercised. Before this task,
 * booting with an unlisted tenant like this would have hit {@code JCacheRegionFactory}'s
 * {@code MissingCacheStrategy} fallback instead of a properly sized cache - this test would have
 * failed the {@code maxCount} assertion below (or, depending on the strategy, failed the whole
 * context) had {@code ensureCacheExists} not created the cache from the {@code entity-region}
 * template first.
 *
 * @see com.empyrean.elide.config.TenantAwareJCacheRegionFactory
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.search.multi_tenancy.tenant_ids=tenant_a,tenant_b,tenant_c")
class TenantWithNoInfinispanXmlEntryTest {

    private static final String NOTE_REGION = Note.class.getName();
    private static final String UNCONFIGURED_TENANT = "tenant_c";

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void addTenantWithNoInfinispanXmlEntry(DynamicPropertyRegistry registry) {
        // No corresponding edit to infinispan.xml - that absence is exactly what this test
        // covers. Overrides the base ids/keys lists wholesale (they're bound as one property
        // each, not merged per-entry), so tenant_c must be listed alongside the base tenants.
        registry.add("svc.tenant.ids", () -> "tenant_a,tenant_b,tenant_c");
        registry.add("svc.tenant.keys", () -> "key-a,key-b,key-c");
    }

    /**
     * The direct assertion: {@code tenant_c}'s region must exist, and must carry the same
     * {@code entity-region} template sizing ({@code max-count=10000}, synchronous invalidation)
     * as the tenants that do have a matching literal entry - proving it was created from the
     * template rather than falling back to an unbounded or default-configured cache.
     */
    @Test
    void tenantWithNoInfinispanXmlEntryGetsACorrectlyBoundedCacheRegion() {
        Cache<Object, Object> cache = embeddedCacheFor(NOTE_REGION + "." + UNCONFIGURED_TENANT);

        assertThat(cache)
                .as("tenant_c has no <invalidation-cache> in infinispan.xml, but the region "
                        + "factory should have created one from the entity-region template at boot")
                .isNotNull();

        var configuration = cache.getCacheConfiguration();
        assertThat(configuration.memory().maxCount())
                .as("the created cache should carry entity-region's sizing, not be unbounded")
                .isEqualTo(10_000L);
        assertThat(configuration.clustering().cacheMode())
                .as("the created cache should be invalidation-mode like every other entity-region cache")
                .isEqualTo(CacheMode.INVALIDATION_SYNC);
    }

    /**
     * Reaches the underlying {@link org.infinispan.manager.EmbeddedCacheManager} the same way
     * {@code TenantAwareJCacheRegionFactory.ensureCacheExists} does, so the assertion inspects the
     * real Infinispan cache rather than the JCache wrapper (which does not expose Infinispan's own
     * {@code Configuration}).
     */
    private Cache<Object, Object> embeddedCacheFor(String regionName) {
        RegionFactory regionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .requireService(RegionFactory.class);
        javax.cache.CacheManager cacheManager = ((JCacheRegionFactory) regionFactory).getCacheManager();
        var embeddedCacheManager = cacheManager.unwrap(org.infinispan.manager.EmbeddedCacheManager.class);
        return embeddedCacheManager.getCache(regionName);
    }
}
