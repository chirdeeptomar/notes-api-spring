package com.empyrean.elide.config;

import com.empyrean.elide.tenant.RequestTenantResolver;
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
}
