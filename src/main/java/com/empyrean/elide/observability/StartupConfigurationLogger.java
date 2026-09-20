package com.empyrean.elide.observability;

import com.empyrean.elide.config.CacheProperties;
import com.empyrean.elide.config.SearchProperties;
import com.empyrean.elide.config.TenancyProperties;
import com.empyrean.elide.tenant.TenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Logs which of tenancy, caching and search are switched on, and which mode each runs in when
 * it is, as the very last thing on startup.
 * <p>
 * All three are independently toggleable and, for caching and search, independently modeable
 * (see {@link TenancyProperties}, {@link CacheProperties}, {@link SearchProperties}) - each
 * combination behaves differently enough (no {@code X-API-KEY} filter vs. enforced, one shared
 * cache region vs. per-tenant, a database {@code LIKE} scan vs. an index lookup) that which
 * combination is actually running is not something to leave to reading properties files or
 * stepping through conditional bean registration. One INFO line per subsystem, plus a summary
 * line, so "what is this deployment actually configured to do" is answered by the first thing
 * in the log rather than inferred from behavior.
 * <p>
 * {@code @Order(Ordered.LOWEST_PRECEDENCE)} runs this after every other {@code ApplicationRunner}
 * in this package (only {@link com.empyrean.elide.tenant.TenantSchemaInitializer}, at
 * {@code @Order(1)}, today) - it reports the configuration that is actually in effect once
 * startup-time provisioning has already happened, not a snapshot from before it.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StartupConfigurationLogger implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StartupConfigurationLogger.class);

    private final TenancyProperties tenancyProperties;
    private final CacheProperties cacheProperties;
    private final SearchProperties searchProperties;
    private final TenantInfo tenantInfo;

    public StartupConfigurationLogger(TenancyProperties tenancyProperties, CacheProperties cacheProperties,
            SearchProperties searchProperties, TenantInfo tenantInfo) {
        this.tenancyProperties = tenancyProperties;
        this.cacheProperties = cacheProperties;
        this.searchProperties = searchProperties;
        this.tenantInfo = tenantInfo;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean tenancyEnabled = tenancyProperties.isEnabled();
        boolean cacheEnabled = cacheProperties.isEnabled();
        boolean searchEnabled = searchProperties.isEnabled();

        LOG.info("Multi-tenancy: {}{}", tenancyEnabled ? "ENABLED" : "DISABLED",
                tenancyEnabled ? ", " + tenantInfo.getTenantCount() + " tenant(s) configured" : "");
        LOG.info("Caching:       {}{}", cacheEnabled ? "ENABLED" : "DISABLED",
                cacheEnabled ? ", mode=" + cacheProperties.getMode() : "");
        LOG.info("Search:        {}{}", searchEnabled ? "ENABLED" : "DISABLED",
                searchEnabled ? ", mode=" + searchProperties.getMode() : "");
    }
}
