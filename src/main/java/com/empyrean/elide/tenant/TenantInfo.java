package com.empyrean.elide.tenant;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the tenant-to-API-key mapping configured under {@code svc.tenant.ids} /
 * {@code svc.tenant.keys}: two parallel comma-separated lists, paired by index
 * ({@code ids[i]} is the tenant whose key is {@code keys[i]}).
 * <p>
 * There is no default/"public" tenant reachable without a key: every request must present an
 * {@code X-API-KEY} matching one of the tenants configured here, or it is rejected - see
 * {@link TenantHeaderFilter}. The tenants listed here are the only valid resolution targets.
 */
@Component
@ConfigurationProperties(prefix = "svc.tenant")
public class TenantInfo {

    private List<String> ids = new ArrayList<>();
    private List<String> keys = new ArrayList<>();

    private Map<String, String> tenantToKey;
    private Map<String, String> keyToTenant;

    public void setIds(List<String> ids) {
        this.ids = ids;
    }

    public List<String> getIds() {
        return ids;
    }

    public void setKeys(List<String> keys) {
        this.keys = keys;
    }

    public List<String> getKeys() {
        return keys;
    }

    /**
     * Fails application startup if {@code svc.tenant.ids} and {@code svc.tenant.keys} are not
     * the same length, or either list contains a duplicate entry. A length mismatch means at
     * least one id or key has no pair; a duplicate id or key would make the id&lt;-&gt;key mapping
     * ambiguous. Runs once both lists are bound (Spring calls setters before
     * {@code @PostConstruct}) and builds the derived lookup maps eagerly, so a later lazy-build
     * race is impossible.
     */
    @PostConstruct
    void validate() {
        if (ids.size() != keys.size()) {
            throw new IllegalStateException(
                    "svc.tenant.ids (" + ids.size() + " entries) and svc.tenant.keys ("
                            + keys.size() + " entries) must have the same length");
        }
        requireNoDuplicates("svc.tenant.ids", ids);
        requireNoDuplicates("svc.tenant.keys", keys);

        Map<String, String> byTenant = new LinkedHashMap<>();
        Map<String, String> byKey = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            byTenant.put(ids.get(i), keys.get(i));
            byKey.put(keys.get(i), ids.get(i));
        }
        this.tenantToKey = byTenant;
        this.keyToTenant = byKey;
    }

    private static void requireNoDuplicates(String propertyName, List<String> values) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (!seen.add(value)) {
                throw new IllegalStateException(
                        propertyName + " contains a duplicate entry: " + value);
            }
        }
    }

    /**
     * @return the names of every configured tenant
     */
    public Set<String> getTenants() {
        return Collections.unmodifiableSet(tenantToKey.keySet());
    }

    /**
     * @return the number of configured tenants
     */
    public int getTenantCount() {
        return tenantToKey.size();
    }

    /**
     * @return an unmodifiable view of the tenant name to API key mapping
     */
    public Map<String, String> getTenantToKeyMapping() {
        return Collections.unmodifiableMap(tenantToKey);
    }

    /**
     * Looks up which tenant a given API key belongs to.
     *
     * @param apiKey the API key presented by the caller
     * @return the matching tenant name, or {@code null} if the key is not configured
     */
    public String getTenantForKey(String apiKey) {
        return keyToTenant.get(apiKey);
    }
}
