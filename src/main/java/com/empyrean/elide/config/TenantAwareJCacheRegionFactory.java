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
import org.infinispan.commons.api.CacheContainerAdmin;
import org.infinispan.jcache.AbstractJCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import javax.cache.Cache;
import javax.cache.CacheManager;
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
 * updating two places that must stay in sync: {@code svc.tenant.ids}/{@code svc.tenant.keys}
 * and that {@code tenant_ids} list - see {@link #ensureCacheExists} for why
 * {@code infinispan.xml} no longer needs a matching per-tenant entry under
 * {@link CacheProperties.Mode#EMBEDDED}.
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
    private final CacheProperties.Mode mode;

    public TenantAwareJCacheRegionFactory(TenantInfo tenantInfo, CacheProperties.Mode mode) {
        this.tenantInfo = tenantInfo;
        this.mode = mode;
    }

    /**
     * @return every tenant that can own a cache region. There is no default/"public" tenant
     *         any more (see {@link TenantInfo}), so this is simply the configured tenant set.
     */
    private Iterable<String> allTenants() {
        return tenantInfo.getTenants();
    }

    @Override
    protected DomainDataStorageAccess createDomainDataStorageAccess(
            DomainDataRegionConfig regionConfig,
            DomainDataRegionBuildingContext buildingContext) {

        String baseRegionName = regionConfig.getRegionName();
        SessionFactoryImplementor sessionFactory = buildingContext.getSessionFactory();
        CacheManager cacheManager = getCacheManager();

        // Resolve every tenant's cache now, at bootstrap, rather than lazily on first
        // use. A
        // missing cache definition in infinispan.xml then fails the application context
        // instead
        // of surfacing as a cache miss storm (or a MissingCacheStrategy error) on the
        // first
        // request that happens to belong to the affected tenant.
        Map<String, Cache<Object, Object>> cachesByTenant = new LinkedHashMap<>();
        for (String tenantId : allTenants()) {
            String regionName = regionName(baseRegionName, tenantId);
            ensureCacheExists(cacheManager, regionName);
            cachesByTenant.put(tenantId, getOrCreateCache(regionName, sessionFactory));
        }

        return new TenantAwareStorageAccess(cachesByTenant);
    }

    /**
     * Makes sure {@code regionName}'s cache exists before Hibernate's own
     * {@link #getOrCreateCache} looks it up, so a tenant with no matching entry in
     * {@code infinispan.xml} still gets a correctly bounded cache instead of one of
     * {@code MissingCacheStrategy}'s fallbacks (an unbounded cache created on the
     * fly, a warning, or a hard failure - see {@code JCacheRegionFactory#createCache}).
     * <p>
     * <b>Embedded only.</b> The JCache {@link CacheManager} handed to this factory
     * unwraps to Infinispan's {@link EmbeddedCacheManager}, whose
     * {@code administration()} API can create a cache from an existing template
     * ({@code entity-region}, still declared in {@code infinispan.xml}) by name
     * alone - which is exactly what makes the tenant list, not the XML file, the
     * source of truth for which per-tenant caches exist. Remote mode's equivalent
     * (an administered {@code RemoteCacheManager}) is a separate, later task; until
     * then remote mode keeps relying on {@code infinispan/hotrod-client.properties}
     * declaring every cache up front, so this method does nothing for it and
     * {@link #getOrCreateCache} below falls through to Hibernate's own lookup
     * unchanged.
     * <p>
     * {@code AdminFlag.VOLATILE} is required, not optional: without it, administration
     * tries to persist the new cache's configuration to Infinispan's global state, which
     * {@code infinispan.xml} does not enable (no {@code <global-state>} element) - and the
     * call fails the whole boot with {@code ISPN000501} instead of creating the cache.
     * Volatile is also the right semantics here regardless: the tenant list is re-read from
     * {@link TenantInfo} on every boot, so nothing should be persisted across restarts.
     * <p>
     * The second call, into {@link AbstractJCacheManager}, is equally required and easy to
     * miss: {@code administration()} creates the cache on the native
     * {@link EmbeddedCacheManager} only. The JCache {@link CacheManager} wrapping it keeps its
     * own private cache-name map, populated at construction and by JCache's own
     * {@code getCache}/{@code createCache} calls - never by the native manager - so without
     * this registration step Hibernate's very next line ({@link #getOrCreateCache}, which
     * looks the region up through the JCache view) would see no cache, call
     * {@code createCache} itself, and fail with Infinispan's "configuration already defined"
     * error, since the native configuration this method just created is already there under
     * the same name. {@code getOrCreateCache} here is {@link AbstractJCacheManager}'s own,
     * distinct from the identically-named {@code JCacheRegionFactory} method below - it takes
     * an already-built native cache and wraps it into the JCache view without redefining
     * anything, and does nothing if that name is already registered.
     */
    private void ensureCacheExists(CacheManager cacheManager, String regionName) {
        if (mode != CacheProperties.Mode.EMBEDDED) {
            return;
        }
        var nativeCache = cacheManager.unwrap(EmbeddedCacheManager.class)
                .administration()
                .withFlags(CacheContainerAdmin.AdminFlag.VOLATILE)
                .getOrCreateCache(regionName, "entity-region");
        cacheManager.unwrap(AbstractJCacheManager.class).getOrCreateCache(regionName, nativeCache);
    }

    /**
     * Region naming: {@code <base region>.<tenant>}, e.g.
     * {@code com.empyrean.elide.model.Note.tenant_a}. Under {@code EMBEDDED} mode this
     * name need not appear in {@code infinispan.xml} at all - {@link #ensureCacheExists}
     * creates it from the {@code entity-region} template. Under {@code REMOTE} mode it
     * still must have a matching entry in {@code infinispan/hotrod-client.properties}.
     */
    private static String regionName(String baseRegionName, String tenantId) {
        return baseRegionName + "." + tenantId;
    }

    /**
     * Routes each operation to the calling session's tenant cache. There is no default/"public"
     * tenant to fall back to any more (see {@link TenantInfo}); an unrecognised tenant identifier
     * resolves to {@code null} (see {@link #cacheForTenant}) rather than to any real tenant's
     * cache.
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

        private TenantAwareStorageAccess(Map<String, Cache<Object, Object>> cachesByTenant) {
            this.cachesByTenant = cachesByTenant;
        }

        private Cache<Object, Object> cacheFor(SharedSessionContractImplementor session) {
            String tenantId = session == null ? null : session.getTenantIdentifier();
            return cacheForTenant(tenantId);
        }

        /**
         * @return the tenant's cache, or {@code null} if {@code tenantId} is null or not a
         *         recognised tenant. Reaching here with an unrecognised tenant means the request
         *         never touched the database - {@code SchemaTenancyStrategy.validateTenant}
         *         already fails hard on unknown identifiers before any query runs - so this
         *         branch is not expected to be hit for real request traffic.
         */
        private Cache<Object, Object> cacheForTenant(String tenantId) {
            return tenantId == null ? null : cachesByTenant.get(tenantId);
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
                        session == null ? "none" : session.getTenantIdentifier());
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
