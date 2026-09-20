package com.empyrean.elide.tenant;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * App-owned abstraction for "turn a tenant id into a scoped JDBC connection", independent of
 * which framework is asking.
 * <p>
 * Two call sites need exactly this logic today - Hibernate ORM's
 * {@link com.empyrean.elide.config.SchemaMultiTenantConnectionProvider} (via Hibernate's own
 * {@code MultiTenantConnectionProvider} SPI) and Elide's aggregation-store
 * {@link com.empyrean.elide.datastore.TenantAwareDataSource} (plain JDBC, since that engine
 * bypasses Hibernate entirely) - and previously implemented it twice, independently. This
 * interface is framework-agnostic (no Hibernate types) precisely so both callers can depend on
 * one implementation identically.
 * <p>
 * Validation is deliberately a separate method from {@link #scopeConnection}, not folded into
 * it: the two existing call sites do not agree on whether to validate or on whether to reset the
 * schema on release, and keeping these as independent methods lets each call site keep its own
 * behavior rather than forcing both to adopt the stricter one.
 */
public interface TenancyStrategy {

    /**
     * Checks that {@code tenantIdentifier} is a known tenant.
     *
     * @param tenantIdentifier the tenant identifier to validate
     * @throws SQLException if the identifier does not name a known tenant
     */
    void validateTenant(String tenantIdentifier) throws SQLException;

    /**
     * Scopes {@code connection} so subsequent statements on it run against
     * {@code tenantIdentifier}'s data.
     *
     * @param connection the connection to scope
     * @param tenantIdentifier the tenant to scope it to
     * @throws SQLException if the underlying driver rejects the scoping operation
     */
    void scopeConnection(Connection connection, String tenantIdentifier) throws SQLException;

    /**
     * Resets {@code connection} back to its non-tenant-specific default scope, so it is safe to
     * reuse (e.g. return to a pool) for a different tenant.
     *
     * @param connection the connection to reset
     * @throws SQLException if the underlying driver rejects the reset operation
     */
    void unscopeConnection(Connection connection) throws SQLException;
}
