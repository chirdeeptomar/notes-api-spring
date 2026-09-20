package com.empyrean.elide.config;

import com.empyrean.elide.observability.QuerySourceStatementInspector;
import com.empyrean.elide.tenant.RequestTenantResolver;
import com.empyrean.elide.tenant.TenantInfo;
import org.hibernate.cache.jcache.ConfigSettings;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    HibernatePropertiesCustomizer tenantResolverCustomizer(
            RequestTenantResolver resolver,
            SchemaMultiTenantConnectionProvider connectionProvider) {
        return props -> {
            props.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
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
     * statically would let the two disagree. The region factory itself is mode-agnostic - it
     * works against the JCache {@code CacheManager}, so tenant scoping is identical either way.
     * <p>
     * When {@code svc.cache.enabled} is false this contributes nothing, leaving Hibernate's
     * second-level cache off and every read going to the database.
     */
    @Bean
    HibernatePropertiesCustomizer cacheRegionFactoryCustomizer(TenantInfo tenantInfo,
            CacheProperties cacheProperties) {
        return props -> {
            if (!cacheProperties.isEnabled()) {
                props.put(AvailableSettings.USE_SECOND_LEVEL_CACHE, "false");
                return;
            }
            props.put(AvailableSettings.USE_SECOND_LEVEL_CACHE, "true");
            props.put(AvailableSettings.CACHE_REGION_FACTORY,
                    new TenantAwareJCacheRegionFactory(tenantInfo));
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
}
