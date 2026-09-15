package com.empyrean.elide.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TenantInfoTest {

    @Autowired
    TenantInfo tenantInfo;

    @Test
    void bindsConfiguredTenants() {
        assertThat(tenantInfo.getTenantCount()).isEqualTo(2);
        assertThat(tenantInfo.getTenants()).containsExactlyInAnyOrder("tenant_a", "tenant_b");
    }

    @Test
    void mapsApiKeyToTenant() {
        assertThat(tenantInfo.getTenantForKey("key-a")).isEqualTo("tenant_a");
        assertThat(tenantInfo.getTenantForKey("key-b")).isEqualTo("tenant_b");
    }

    @Test
    void unknownKeyMapsToNull() {
        assertThat(tenantInfo.getTenantForKey("not-a-real-key")).isNull();
    }

    @Test
    void defaultTenantIsPublicAndNotKeyReachable() {
        assertThat(tenantInfo.getDefaultTenant()).isEqualTo("public");
        assertThat(tenantInfo.getTenants()).doesNotContain("public");
    }

    @Test
    void tenantContextRoundTripsAndClears() {
        TenantContext.set("tenant_a");
        assertThat(TenantContext.get()).isEqualTo("tenant_a");
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void accessorsReturnUnmodifiableViews() {
        assertThatThrownBy(() -> tenantInfo.getTenants().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tenantInfo.getTenantToKeyMapping().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
