package com.empyrean.elide.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SchemaMultiTenantConnectionProvider} is the single chokepoint where a tenant
 * identifier is translated into an actual schema on a JDBC connection. No HTTP filter feeds it
 * an untrusted identifier yet, but when one is written, a bug in it must not be able to route a
 * connection to an arbitrary schema - it should fail hard instead. This test proves the guard
 * that makes that true.
 */
@SpringBootTest
class SchemaMultiTenantConnectionProviderTest {

    @Autowired
    SchemaMultiTenantConnectionProvider provider;

    @Test
    void rejectsUnknownTenantIdentifier() {
        assertThatThrownBy(() -> {
            try (Connection ignored = provider.getConnection("not-a-configured-tenant")) {
                // unreachable
            }
        }).isInstanceOf(SQLException.class)
                .hasMessageContaining("Unknown tenant");
    }

    @Test
    void acceptsTheDefaultTenant() throws SQLException {
        try (Connection connection = provider.getConnection("public")) {
            provider.releaseConnection("public", connection);
        }
    }

    @Test
    void acceptsAConfiguredKeyProtectedTenant() throws SQLException {
        try (Connection connection = provider.getConnection("tenant_a")) {
            provider.releaseConnection("tenant_a", connection);
        }
    }
}
