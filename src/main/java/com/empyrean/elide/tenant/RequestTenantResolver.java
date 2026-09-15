package com.empyrean.elide.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Tells Hibernate ORM's schema-per-tenant multi-tenancy which schema to use for the current
 * unit of work, based on the tenant resolved earlier by {@code TenantHeaderFilter} (Task 5) and
 * held in {@link TenantContext}.
 * <p>
 * Registered with Hibernate by
 * {@link com.empyrean.elide.config.HibernateTenancyConfiguration}.
 */
@Component
public class RequestTenantResolver implements CurrentTenantIdentifierResolver<String> {

    private final TenantInfo tenantInfo;

    public RequestTenantResolver(TenantInfo tenantInfo) {
        this.tenantInfo = tenantInfo;
    }

    /**
     * @return the tenant for the in-flight request, or the default tenant when none is in
     *         scope - which covers Hibernate's own boot-time schema generation and any
     *         startup or background job that has not explicitly overridden the tenant
     */
    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.get();
        return tenantId != null ? tenantId : tenantInfo.getDefaultTenant();
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
