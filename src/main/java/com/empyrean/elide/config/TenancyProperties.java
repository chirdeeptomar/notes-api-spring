package com.empyrean.elide.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Switches multi-tenancy on or off entirely, under {@code svc.tenancy.enabled}.
 * <p>
 * A structural twin of {@link CacheProperties}/{@link SearchProperties} - one small
 * {@code @ConfigurationProperties} class per subsystem toggle. Deliberately not a field on
 * {@link com.empyrean.elide.tenant.TenantInfo}: "is tenancy on at all" is a different concern
 * from "the tenant registry", and several consumers (the filter, the Hibernate multi-tenancy
 * wiring, the cache region factory, the two raw-JDBC {@code DataSource} construction sites) need
 * this flag independently of whether they also need the tenant-key map.
 * <p>
 * Defaults to {@code false}: a deployment that never sets this property gets a single-tenant
 * application by default, not a multi-tenant one - none of the tenancy machinery is registered
 * at all, and the deployment behaves as if there were exactly one implicit tenant: no
 * {@code X-API-KEY} filter, no Hibernate multi-tenancy SPI, one shared cache region per entity,
 * and a plain undecorated {@code DataSource} on both JDBC paths. Multi-tenancy is opt-in via
 * {@code svc.tenancy.enabled=true}, which registers the {@code X-API-KEY} filter, Hibernate's
 * multi-tenancy SPI, per-tenant cache regions, and routes both JDBC paths through
 * {@link com.empyrean.elide.tenant.TenancyStrategy}.
 */
@Component
@ConfigurationProperties(prefix = "svc.tenancy")
public class TenancyProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
