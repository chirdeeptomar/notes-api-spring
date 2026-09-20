package com.empyrean.elide.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Owns the tenant-to-API-key mapping configured under {@code svc.tenant.*} (each property
 * name after the prefix is a tenant name; its value is that tenant's API key), plus the
 * identity of the default tenant that needs no key.
 * <p>
 * The default tenant ({@link #getDefaultTenant()}) is deliberately not part of
 * {@code svc.tenant.*} - it is not reachable by any key, only by omitting the
 * {@code X-API-KEY} header.
 */
@Component
@ConfigurationProperties(prefix = "svc")
public class TenantInfo {

    private static final String DEFAULT_TENANT = "public";

    /** Bound from {@code svc.tenant.<name>=<key>}. */
    private Map<String, String> tenant = new LinkedHashMap<>();

    private Map<String, String> keyToTenant;

    public void setTenant(Map<String, String> tenant) {
        this.tenant = tenant;
        this.keyToTenant = null;
    }

    public Map<String, String> getTenant() {
        return tenant;
    }

    /**
     * @return the names of every configured, key-protected tenant (excludes the default tenant)
     */
    public Set<String> getTenants() {
        return Collections.unmodifiableSet(tenant.keySet());
    }

    /**
     * @return the number of configured, key-protected tenants (excludes the default tenant)
     */
    public int getTenantCount() {
        return tenant.size();
    }

    /**
     * @return an unmodifiable view of the tenant name to API key mapping
     */
    public Map<String, String> getTenantToKeyMapping() {
        return Collections.unmodifiableMap(tenant);
    }

    /**
     * Looks up which tenant a given API key belongs to. The reverse (key-to-tenant) index is
     * built lazily on first use and cached.
     *
     * @param apiKey the API key presented by the caller
     * @return the matching tenant name, or {@code null} if the key is not configured
     */
    public String getTenantForKey(String apiKey) {
        if (keyToTenant == null) {
            Map<String, String> inverted = new HashMap<>();
            tenant.forEach((name, key) -> inverted.put(key, name));
            keyToTenant = inverted;
        }
        return keyToTenant.get(apiKey);
    }

    /**
     * @return the name of the tenant used when no API key is presented; this tenant requires
     * no key and is not present in {@link #getTenantToKeyMapping()}
     */
    public String getDefaultTenant() {
        return DEFAULT_TENANT;
    }
}
