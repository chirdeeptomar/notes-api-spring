package com.empyrean.elide.config;

import com.empyrean.elide.observability.QuerySourceRecorder;
import com.empyrean.elide.tenant.TenantInfo;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.cache.cfg.spi.DomainDataRegionBuildingContext;
import org.hibernate.cache.cfg.spi.DomainDataRegionConfig;
import org.hibernate.cache.jcache.internal.JCacheRegionFactory;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import javax.cache.Cache;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link JCacheRegionFactory} that gives every tenant its own Infinispan
 * cache per entity
 * region, so one tenant's reads can never consume another tenant's eviction
 * budget.
 * <p>
 * <b>Why this exists at all.</b> Hibernate's second-level entity cache is
 * already correct under
 * multi-tenancy without this class: {@code EntityDataAccess#generateCacheKey}
 * is handed the
 * current tenant identifier and {@code DefaultCacheKeysFactory} folds it into
 * the
 * {@code CacheKeyImplementation}, so {@code tenant_a} and {@code tenant_b}
 * reading the same row
 * id produce two distinct keys and never see each other's data. What plain
 * key-scoping does NOT
 * give is <em>capacity</em> isolation: with a single cache per entity, all
 * tenants share one
 * {@code max-count}, so a tenant running a large scan evicts a quieter tenant's
 * hot entries.
 * This factory adds the second layer - one region per (entity, tenant) - so
 * each tenant's
 * working set is sized, evicted and monitored independently.
 * <p>
 * <b>Why the routing lives here and not in {@code getOrCreateCache}.</b> The
 * obvious approach -
 * overriding {@code getOrCreateCache} to append a tenant suffix - cannot work.
 * {@code getOrCreateCache} is called once per region while the
 * {@code SessionFactory} is being
 * built, long before any request exists, so {@code TenantContext} is empty at
 * that point. The
 * tenant is only knowable per operation, and the seam that sees it is
 * {@link DomainDataStorageAccess}: every one of its read/write methods receives
 * the
 * {@link SharedSessionContractImplementor}, whose {@code getTenantIdentifier()}
 * is populated by
 * {@link com.empyrean.elide.tenant.RequestTenantResolver}. Hibernate's own
 * {@code JCacheAccessImpl} holds a single cache and ignores that session
 * argument; the inner
 * class below is the same thing with the session argument actually used.
 * <p>
 * <b>The session-less methods are the sharp edge.</b> {@code evictData()} and
 * {@code evictData(Object)} carry no session, so they cannot resolve a tenant.
 * They are what
 * {@code SessionFactory.getCache().evictAllRegions()} and Hibernate's own
 * region-management
 * calls go through. If those only touched one tenant's cache, an eviction
 * intended to clear
 * everything would leave every other tenant serving stale rows - the failure
 * would be silent
 * and would look exactly like a cache-coherence bug. They therefore fan out
 * across all known
 * tenants. Note the inverse trap: {@link DomainDataStorageAccess}'s
 * {@code removeFromCache} and
 * {@code clearCache} <em>default</em> to delegating to the session-less
 * {@code evictData}, which
 * would turn an ordinary single-tenant write into a cross-tenant eviction; both
 * are overridden
 * below to use the session's tenant instead.
 * <p>
 * <b>Tenant list is fixed at startup.</b> Regions are resolved from
 * {@link TenantInfo} when the
 * {@code SessionFactory} is built, so adding a tenant requires a restart. This
 * matches how the
 * rest of the application already treats tenancy -
 * {@code hibernate.search.multi_tenancy.tenant_ids}
 * in {@code application.properties} is likewise a static list. Adding a tenant
 * therefore means
 * updating three places that must stay in sync: {@code notes.tenant.*}, that
 * {@code tenant_ids} list, and {@code infinispan.xml}'s per-tenant cache
 * definitions.
 *
 * <b>Registration.</b> This factory needs {@link TenantInfo} injected, so it cannot be named by
 * class in {@code hibernate.cache.region.factory_class} - Hibernate's {@code StrategySelector}
 * instantiates a named class through its no-arg constructor, outside the Spring container.
 * {@link HibernateTenancyConfiguration} therefore registers a fully constructed <em>instance</em>
 * under that same setting, which {@code StrategySelector} accepts and returns as-is. This is the
 * same pattern, and for the same reason, as the {@code RequestTenantResolver} and
 * {@code SchemaMultiTenantConnectionProvider} beans registered alongside it.
 *
 * @see com.empyrean.elide.tenant.RequestTenantResolver
 * @see SchemaMultiTenantConnectionProvider
 * @see HibernateTenancyConfiguration
 */
@Slf4j
public class TenantAwareJCacheRegionFactory extends JCacheRegionFactory {

    private final TenantInfo tenantInfo;

    public TenantAwareJCacheRegionFactory(TenantInfo tenantInfo) {
        this.tenantInfo = tenantInfo;
    }

    /**
     * @return every tenant that can own a cache region: the key-protected tenants
     *         plus the
     *         default tenant, which owns no API key but is a real schema
     *         ({@link TenantInfo#getDefaultTenant()})
     */
    private Iterable<String> allTenants() {
        var tenants = new java.util.LinkedHashSet<String>();
        tenants.add(tenantInfo.getDefaultTenant());
        tenants.addAll(tenantInfo.getTenants());
        return tenants;
    }

    @Override
    protected DomainDataStorageAccess createDomainDataStorageAccess(
            DomainDataRegionConfig regionConfig,
            DomainDataRegionBuildingContext buildingContext) {

        String baseRegionName = regionConfig.getRegionName();
        SessionFactoryImplementor sessionFactory = buildingContext.getSessionFactory();

        // Resolve every tenant's cache now, at bootstrap, rather than lazily on first
        // use. A
        // missing cache definition in infinispan.xml then fails the application context
        // instead
        // of surfacing as a cache miss storm (or a MissingCacheStrategy error) on the
        // first
        // request that happens to belong to the affected tenant.
        Map<String, Cache<Object, Object>> cachesByTenant = new LinkedHashMap<>();
        for (String tenantId : allTenants()) {
            cachesByTenant.put(tenantId, getOrCreateCache(regionName(baseRegionName, tenantId), sessionFactory));
        }

        return new TenantAwareStorageAccess(cachesByTenant, tenantInfo.getDefaultTenant());
    }

    /**
     * Region naming: {@code <base region>.<tenant>}, e.g.
     * {@code com.empyrean.elide.model.Note.tenant_a}. Each name must have a
     * matching cache in
     * {@code infinispan.xml}.
     */
    private static String regionName(String baseRegionName, String tenantId) {
        return baseRegionName + "." + tenantId;
    }

    /**
     * Routes each operation to the calling session's tenant cache, falling back to
     * the default
     * tenant when no tenant is in scope (Hibernate's own boot-time and background
     * work, which
     * {@code RequestTenantResolver} likewise resolves to the default tenant).
     * <p>
     * Per-lookup tracing logs through the <em>outer</em> class's logger deliberately. Putting
     * {@code @Slf4j} here instead names the logger
     * {@code ...TenantAwareJCacheRegionFactory$TenantAwareStorageAccess}, and Spring treats
     * {@code $} in a properties key as a placeholder delimiter - so
     * {@code logging.level....$TenantAwareStorageAccess=TRACE} never binds and the level silently
     * has no effect. The outer class's name contains no {@code $} and can actually be configured.
     */
    private static final class TenantAwareStorageAccess implements DomainDataStorageAccess {

        private final Map<String, Cache<Object, Object>> cachesByTenant;
        private final String defaultTenant;

        private TenantAwareStorageAccess(Map<String, Cache<Object, Object>> cachesByTenant, String defaultTenant) {
            this.cachesByTenant = cachesByTenant;
            this.defaultTenant = defaultTenant;
        }

        private Cache<Object, Object> cacheFor(SharedSessionContractImplementor session) {
            String tenantId = session == null ? null : session.getTenantIdentifier();
            return cacheForTenant(tenantId);
        }

        private Cache<Object, Object> cacheForTenant(String tenantId) {
            Cache<Object, Object> cache = tenantId == null ? null : cachesByTenant.get(tenantId);
            if (cache != null) {
                return cache;
            }
            // An unknown tenant must not silently share the default tenant's cache, but
            // this
            // layer is not the right place to reject it either -
            // SchemaMultiTenantConnectionProvider
            // already fails hard on unknown identifiers before any query runs, so reaching
            // here
            // with an unrecognised tenant means the request never touched the database.
            return cachesByTenant.get(defaultTenant);
        }

        @Override
        public Object getFromCache(Object key, SharedSessionContractImplementor session) {
            Object value = cacheFor(session).get(key);
            // Every second-level cache read funnels through here, which is what makes this the
            // one honest place to count hits and misses: Hibernate's own Statistics are
            // process-wide and per-region, so they cannot attribute a hit to the request that
            // caused it. A miss here is followed by a database load, which reports itself
            // separately - see QuerySourceRecorder.
            QuerySourceRecorder.recordCacheLookup(value != null);
            if (log.isTraceEnabled()) {
                log.trace("L2 {} region={} tenant={}",
                        value != null ? "HIT" : "MISS",
                        regionNameFor(session),
                        session == null ? defaultTenant : session.getTenantIdentifier());
            }
            return value;
        }

        /**
         * Best-effort region name for trace logging only. The caches are keyed by tenant, so the
         * name is recovered from the JCache entry rather than stored separately.
         */
        private String regionNameFor(SharedSessionContractImplementor session) {
            Cache<Object, Object> cache = cacheFor(session);
            return cache == null ? "unknown" : cache.getName();
        }

        @Override
        public void putIntoCache(Object key, Object value, SharedSessionContractImplementor session) {
            cacheFor(session).put(key, value);
        }

        /**
         * Overridden because the interface default delegates to the session-less
         * {@link #evictData(Object)}, which fans out across every tenant - turning one
         * tenant's
         * ordinary write into a cluster-wide, cross-tenant eviction.
         */
        @Override
        public void removeFromCache(Object key, SharedSessionContractImplementor session) {
            cacheFor(session).remove(key);
        }

        /**
         * Overridden for the same reason as {@link #removeFromCache}: the interface
         * default
         * would clear every tenant's cache, not the caller's.
         */
        @Override
        public void clearCache(SharedSessionContractImplementor session) {
            cacheFor(session).clear();
        }

        @Override
        public boolean contains(Object key) {
            // No session, so no tenant: report a hit if ANY tenant holds the key. Hibernate
            // uses
            // this only for diagnostics/assertions, never to decide whether to serve a row,
            // so a
            // cross-tenant answer here cannot leak data.
            for (Cache<Object, Object> cache : cachesByTenant.values()) {
                if (cache.containsKey(key)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Session-less: clears the region for EVERY tenant. See the class javadoc -
         * anything
         * narrower would leave other tenants stale after an explicit region eviction.
         */
        @Override
        public void evictData() {
            cachesByTenant.values().forEach(Cache::clear);
        }

        /**
         * Session-less: removes the key from EVERY tenant's cache. The same row id can
         * legitimately
         * exist in more than one tenant's schema, so there is no single correct tenant
         * to pick.
         */
        @Override
        public void evictData(Object key) {
            cachesByTenant.values().forEach(cache -> cache.remove(key));
        }

        @Override
        public void release() {
            cachesByTenant.values().forEach(Cache::close);
        }
    }
}
