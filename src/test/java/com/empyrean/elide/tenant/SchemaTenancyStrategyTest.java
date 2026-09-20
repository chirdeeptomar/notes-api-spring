package com.empyrean.elide.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tests {@link SchemaTenancyStrategy} directly against a real H2 connection, without a full
 * {@code @SpringBootTest} context - this is pure schema-routing logic with no Spring dependency
 * of its own, so a plain JDBC connection is enough to prove it.
 */
class SchemaTenancyStrategyTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:schema-tenancy-strategy-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";

    private Connection connection;
    private SchemaTenancyStrategy strategy;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS tenant_a");
        }

        TenantInfo tenantInfo = new TenantInfo();
        Map<String, String> tenants = new LinkedHashMap<>();
        tenants.put("tenant_a", "key-a");
        tenantInfo.setTenant(tenants);

        strategy = new SchemaTenancyStrategy(tenantInfo);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @Test
    void validateTenantAcceptsTheDefaultTenant() throws SQLException {
        strategy.validateTenant("public");
        // no exception
    }

    @Test
    void validateTenantAcceptsAConfiguredTenant() throws SQLException {
        strategy.validateTenant("tenant_a");
        // no exception
    }

    @Test
    void validateTenantRejectsAnUnknownTenant() {
        assertThatThrownBy(() -> strategy.validateTenant("not-a-configured-tenant"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Unknown tenant");
    }

    @Test
    void scopeConnectionSetsTheConnectionSchema() throws SQLException {
        strategy.scopeConnection(connection, "tenant_a");
        assertThat(connection.getSchema()).isEqualToIgnoringCase("tenant_a");
    }

    @Test
    void unscopeConnectionResetsToTheDefaultTenantSchema() throws SQLException {
        strategy.scopeConnection(connection, "tenant_a");
        assertThat(connection.getSchema()).isEqualToIgnoringCase("tenant_a");

        strategy.unscopeConnection(connection);
        assertThat(connection.getSchema()).isEqualToIgnoringCase("public");
    }
}
