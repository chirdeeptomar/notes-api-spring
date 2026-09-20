package com.empyrean.elide.config;

import com.empyrean.elide.observability.QuerySourceStatementInspector;
import com.empyrean.elide.tenant.RequestTenantResolver;
import com.empyrean.elide.tenant.TenantInfo;
import org.hibernate.cache.jcache.ConfigSettings;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Registers {@link RequestTenantResolver} and {@link SchemaMultiTenantConnectionProvider} as
 * Hibernate's current-tenant resolver and multi-tenant connection provider, respectively.
 * <p>
 * Both are required for Hibernate 7's {@code SCHEMA} multi-tenancy strategy to actually route
 * work to different schemas: the resolver only answers "which tenant is current"; the
 * connection provider is what applies that tenant's schema to a JDBC connection and - just as
 * important - resets it before the connection returns to the pool.
 * <p>
 * Done through a {@link HibernatePropertiesCustomizer} (Spring Boot 4.1's
 * {@code spring-boot-hibernate} module) rather than plain properties because
 * both beans have their own dependencies (a {@link javax.sql.DataSource}, {@code TenantInfo}) -
 * naming the classes in {@code spring.jpa.properties} would have Hibernate instantiate its own
 * copies, outside the container and without those dependencies injected.
 */
@Configuration
public class HibernateTenancyConfiguration {

    /**
     * When {@code svc.tenancy.enabled=false}, contributes neither
     * {@code MULTI_TENANT_IDENTIFIER_RESOLVER} nor {@code MULTI_TENANT_CONNECTION_PROVIDER} -
     * this is what actually removes Hibernate's multi-tenancy SPI. Hibernate 7 enables
     * multi-tenancy purely by presence: {@code MultiTenancy.isMultiTenancyEnabled} just checks
     * whether a {@code MultiTenantConnectionProvider} service was registered, so withholding
     * both properties here is mechanically complete on its own - there is no separate "set the
     * strategy to NONE" step needed (Hibernate 7 has no such enum-typed setting any more).
     * <p>
     * {@code resolver} is {@code Optional} because {@link RequestTenantResolver} itself is now
     * conditionally registered (see its javadoc for why that bean-level gate, not just this
     * customizer, is what actually keeps Hibernate from picking it up when tenancy is disabled).
     * When disabled, the {@code Optional} is empty and this method never touches it.
     */
    @Bean
    HibernatePropertiesCustomizer tenantResolverCustomizer(
            Optional<RequestTenantResolver> resolver,
            SchemaMultiTenantConnectionProvider connectionProvider,
            TenancyProperties tenancyProperties) {
        return props -> {
            if (!tenancyProperties.isEnabled()) {
                return;
            }
            props.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver.orElseThrow());
            props.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
        };
    }

    /**
     * Registers {@link TenantAwareJCacheRegionFactory} as Hibernate's second-level cache region
     * factory, so each tenant gets its own Infinispan cache per entity region, and points it at
     * the JCache provider for the configured {@link CacheProperties.Mode}.
     * <p>
     * Registered as a constructed <em>instance</em> rather than by class name in
     * {@code application.properties}: the factory needs {@link TenantInfo} injected, and
     * Hibernate's {@code StrategySelector} instantiates a class named in
     * {@code hibernate.cache.region.factory_class} through its no-arg constructor, outside the
     * Spring container. {@code StrategySelector} accepts an already-built instance for that same
     * setting and returns it as-is - the same reason the resolver and connection provider above
     * are registered this way.
     * <p>
     * The provider class and config URI are set here rather than in
     * {@code application.properties} because both must follow {@code svc.cache.mode}: embedded
     * and remote need different provider classes and different config formats, and setting them
     * statically would let the two disagree. The mode is also passed into the region factory
     * itself, alongside {@code TenantInfo} - not because tenant scoping differs (it works against
     * the JCache {@code CacheManager}, so that part is identical either way), but because only
     * embedded mode can create a tenant's cache on the fly from a template; see
     * {@code TenantAwareJCacheRegionFactory#ensureCacheExists}.
     * <p>
     * When {@code svc.cache.enabled} is false this contributes nothing, leaving Hibernate's
     * second-level cache off and every read going to the database.
     * <p>
     * When {@code svc.tenancy.enabled} is false, the tenant-aware region factory is not
     * registered even if caching itself is on: Hibernate Boot's own plain default region
     * factory applies instead, giving one shared region per entity rather than one per
     * (entity, tenant) - the literal "no per-tenant cache region splitting" requirement of the
     * disabled state. {@link TenantAwareJCacheRegionFactory} itself needs no change for this;
     * it is purely a bean-selection decision here.
     */
    @Bean
    HibernatePropertiesCustomizer cacheRegionFactoryCustomizer(TenantInfo tenantInfo,
            CacheProperties cacheProperties, TenancyProperties tenancyProperties) {
        return props -> {
            if (!cacheProperties.isEnabled()) {
                props.put(AvailableSettings.USE_SECOND_LEVEL_CACHE, "false");
                return;
            }
            props.put(AvailableSettings.USE_SECOND_LEVEL_CACHE, "true");
            if (tenancyProperties.isEnabled()) {
                props.put(AvailableSettings.CACHE_REGION_FACTORY,
                        new TenantAwareJCacheRegionFactory(tenantInfo, cacheProperties.getMode()));
            }
            props.put(ConfigSettings.PROVIDER, cacheProperties.cachingProvider());
            props.put(ConfigSettings.CONFIG_URI, cacheProperties.resolvedConfigUri());
        };
    }

    /**
     * Registers the {@link QuerySourceStatementInspector} so every SQL read is attributable to the
     * request that caused it, which is what lets one log line state whether the cache, the index
     * or the database served a request.
     * <p>
     * Registered as a constructed instance rather than by class name for the same reason as the
     * region factory above: Hibernate instantiates a named class through its no-arg constructor
     * outside the Spring container. The inspector happens to need no dependencies today, but
     * registering it the same way keeps both on one mechanism rather than two that look alike.
     * <p>
     * This is independent of {@code spring.jpa.show-sql}: that prints statements, this attributes
     * them. Leaving {@code show-sql} on remains useful for seeing the SQL text itself.
     */
    @Bean
    HibernatePropertiesCustomizer statementInspectorCustomizer() {
        return props -> props.put(AvailableSettings.STATEMENT_INSPECTOR,
                new QuerySourceStatementInspector());
    }

    /**
     * Sets {@code hibernate.search.multi_tenancy.tenant_ids} programmatically from
     * {@link TenantInfo}, gated by {@code svc.tenancy.enabled} - a static
     * {@code application.properties} entry cannot do either.
     * <p>
     * Two independent reasons this must be conditional, not just derived: (1) Hibernate Search's
     * own multi-tenancy strategy is separate from Hibernate ORM's - leaving a static tenant-ids
     * list in place while ORM's multi-tenancy SPI is unregistered (disabled state) makes every
     * index operation fail with "Invalid tenant identifiers... multi-tenancy is disabled for
     * this backend", because Hibernate Search then expects to be given NO tenant identifiers at
     * all, not an empty-but-present list; (2) deriving the list from {@link TenantInfo} instead
     * of hand-maintaining it in properties closes the separate static-list-sync gap that class's
     * own javadoc flags (adding a tenant to {@code svc.tenant.ids} now automatically updates this
     * list too, with no second edit).
     */
    @Bean
    HibernatePropertiesCustomizer searchTenantIdsCustomizer(TenantInfo tenantInfo,
            TenancyProperties tenancyProperties) {
        return props -> {
            if (!tenancyProperties.isEnabled()) {
                return;
            }
            String tenantIds = String.join(",", tenantInfo.getTenants());
            props.put("hibernate.search.multi_tenancy.tenant_ids", tenantIds);
        };
    }

    /**
     * Sets every {@code hibernate.search.backend.*} property from {@link SearchProperties},
     * branching on {@link SearchProperties#getMode()} - the same reason
     * {@link #cacheRegionFactoryCustomizer} sets {@code CacheProperties}' provider/URI here
     * rather than statically: a single {@code svc.search.mode} switch has to change several
     * dependent properties together (backend type, host, credentials, version) without a static
     * {@code application.properties} entry and this switch ever disagreeing. Previously this was
     * split across three static {@code application*.properties} files
     * ({@code application-opensearch.properties}, {@code application-elasticsearch.properties})
     * that each hardcoded {@code backend.type=elasticsearch} and only really varied
     * {@code backend.version}'s prefix - collapsing that into one property-driven customizer is
     * what makes {@code SVC_SEARCH_MODE=opensearch} alone sufficient, mirroring how
     * {@code SVC_CACHE_MODE=remote} alone is already sufficient for caching.
     * <p>
     * Runs regardless of {@code svc.search.enabled}, unlike every other customizer in this
     * class: Hibernate Search's own bootstrap is driven by {@code Note}'s {@code @Indexed}
     * annotation, at the ORM level, independently of whether
     * {@link com.empyrean.elide.config.ElideStoreConfiguration} chooses to wrap {@code Note} in
     * a {@code SearchDataStore} - {@code svc.search.enabled} only controls the latter. Without
     * {@code hibernate.search.backend.type} set, Hibernate Search's own bootstrap fails outright
     * with {@code HSEARCH000582: Ambiguous backend type} (both the Lucene and Elasticsearch
     * backend jars are on the classpath) even with search "disabled" - so this must contribute
     * the backend type unconditionally, the same as the static
     * {@code hibernate.search.backend.type=lucene} property this customizer replaced.
     * <p>
     * {@code directory.type} is the one Lucene setting set with {@code putIfAbsent}, not
     * {@code put}: {@code build.gradle}'s {@code test} task forces it to {@code local-heap} via a
     * JVM system property (an in-memory directory with no file lock, avoiding write.lock
     * contention between {@code @SpringBootTest} contexts that otherwise share one
     * {@code build/lucene-indexes/Note} directory on disk). A plain {@code put} here would
     * unconditionally overwrite that system property's value - {@code HibernatePropertiesCustomizer}
     * mutates the already-precedence-resolved properties map, so it always wins over whatever
     * property source contributed the map's current value, unlike a static
     * {@code application.properties} entry which the system property could still outrank.
     * {@code putIfAbsent} preserves that override while still supplying the same default outside
     * tests.
     */
    @Bean
    HibernatePropertiesCustomizer searchBackendCustomizer(SearchProperties searchProperties) {
        return props -> {
            switch (searchProperties.getMode()) {
                case LUCENE -> {
                    props.put("hibernate.search.backend.type", "lucene");
                    props.putIfAbsent("hibernate.search.backend.directory.type", "local-filesystem");
                    props.put("hibernate.search.backend.directory.root", searchProperties.getIndexPath());
                }
                case OPENSEARCH, ELASTICSEARCH -> {
                    props.put("hibernate.search.backend.type", "elasticsearch");
                    props.put("hibernate.search.backend.hosts", searchProperties.getHosts());
                    props.put("hibernate.search.backend.protocol", searchProperties.getProtocol());
                    props.put("hibernate.search.backend.username", searchProperties.getUsername());
                    props.put("hibernate.search.backend.password", searchProperties.getPassword());
                    props.put("hibernate.search.backend.version", searchProperties.resolvedBackendVersion());
                    props.put("hibernate.search.backend.version_check.enabled",
                            String.valueOf(searchProperties.isVersionCheckEnabled()));
                }
            }
        };
    }
}
