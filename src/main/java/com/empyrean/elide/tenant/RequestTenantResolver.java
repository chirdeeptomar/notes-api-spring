package com.empyrean.elide.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tells Hibernate ORM's schema-per-tenant multi-tenancy which schema to use for the current
 * unit of work, based on the tenant resolved earlier by {@code TenantHeaderFilter} (Task 5) and
 * held in {@link TenantContext}.
 * <p>
 * Registered with Hibernate by
 * {@link com.empyrean.elide.config.HibernateTenancyConfiguration}.
 * <p>
 * Gated on {@code svc.tenancy.enabled} for a subtler reason than the other conditional beans in
 * this package: Spring Boot's Hibernate autoconfiguration auto-detects any single
 * {@code CurrentTenantIdentifierResolver} bean in the context and wires it into
 * {@code hibernate.tenant_identifier_resolver} on its own, independently of whether
 * {@code HibernateTenancyConfiguration}'s {@code tenantResolverCustomizer} chooses to contribute
 * that property. Leaving this bean unconditional let it get auto-registered even when tenancy is
 * disabled - Hibernate's {@code SessionFactoryImpl.resolveTenantIdentifier()} calls a registered
 * resolver unconditionally (with no check on whether multi-tenancy itself is enabled) whenever a
 * new {@code Session}/{@code EntityManager} is opened without an explicit tenant, which reached
 * this class's fallback ({@link SchemaTenancyStrategy#PHYSICAL_DEFAULT_SCHEMA}, i.e.
 * {@code "public"}) on every such session - including the one Hibernate Search's mass-indexer
 * opens during {@code Elide} bean construction - and that non-null tenant id then failed with
 * {@code HSEARCH600031} because Hibernate Search had correctly determined single-tenancy mode
 * and expected none. Not registering this bean at all when disabled is what actually prevents
 * that: with no {@code CurrentTenantIdentifierResolver} bean present, Spring has nothing to
 * auto-wire, and {@code resolveTenantIdentifier()} returns {@code null} as it should.
 */
@Component
@ConditionalOnProperty(name = "svc.tenancy.enabled", havingValue = "true", matchIfMissing = false)
public class RequestTenantResolver implements CurrentTenantIdentifierResolver<String> {

    private final TenantInfo tenantInfo;

    public RequestTenantResolver(TenantInfo tenantInfo) {
        this.tenantInfo = tenantInfo;
    }

    /**
     * @return the tenant for the in-flight request, or
     *         {@link SchemaTenancyStrategy#PHYSICAL_DEFAULT_SCHEMA} when none is in scope - which
     *         covers Hibernate's own boot-time schema generation and any startup or background
     *         job that has not explicitly overridden the tenant.
     *         <p>
     *         This must be a schema that already physically exists at that point, not an
     *         arbitrary configured tenant: Hibernate Search's mass-indexer runs during
     *         {@code Elide} bean construction - well before
     *         {@code TenantSchemaInitializer}'s {@code ApplicationRunner} phase provisions any
     *         real tenant's schema - and it genuinely opens a connection scoped to whatever this
     *         method returns, rather than treating it as opaque bookkeeping. Falling back to a
     *         real tenant name here fails that mass-indexing pass with "Schema not found", since
     *         the tenant's schema does not exist yet.
     */
    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.get();
        return tenantId != null ? tenantId : SchemaTenancyStrategy.PHYSICAL_DEFAULT_SCHEMA;
    }

    /**
     * @return {@code true}, so Hibernate validates that a cached entity's tenant matches the
     *         current one rather than serving one tenant's row to another
     */
    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
