package com.empyrean.elide.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

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
    void thereIsNoDefaultTenant() {
        // "public" is not a configured tenant - there is no key-less default any more.
        assertThat(tenantInfo.getTenants()).doesNotContain("public");
        assertThat(tenantInfo.getTenantForKey(null)).isNull();
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

    @Test
    void validateRejectsMismatchedLength() {
        TenantInfo info = new TenantInfo();
        info.setIds(List.of("tenant_a", "tenant_b"));
        info.setKeys(List.of("key-a"));
        assertThatThrownBy(info::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validateRejectsDuplicateIds() {
        TenantInfo info = new TenantInfo();
        info.setIds(List.of("tenant_a", "tenant_a"));
        info.setKeys(List.of("key-a", "key-b"));
        assertThatThrownBy(info::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validateRejectsDuplicateKeys() {
        TenantInfo info = new TenantInfo();
        info.setIds(List.of("tenant_a", "tenant_b"));
        info.setKeys(List.of("key-a", "key-a"));
        assertThatThrownBy(info::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validateSucceedsAndBindsWhenListsAreConsistent() {
        TenantInfo info = new TenantInfo();
        info.setIds(List.of("tenant_a", "tenant_b"));
        info.setKeys(List.of("key-a", "key-b"));
        info.validate();
        assertThat(info.getTenants()).containsExactlyInAnyOrder("tenant_a", "tenant_b");
        assertThat(info.getTenantForKey("key-a")).isEqualTo("tenant_a");
    }
}
