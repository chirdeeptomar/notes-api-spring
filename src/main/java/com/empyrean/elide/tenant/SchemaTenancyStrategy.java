package com.empyrean.elide.tenant;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * {@link TenancyStrategy} that isolates tenants by database schema, via
 * {@link Connection#setSchema(String)}.
 * <p>
 * Holds exactly the schema-routing logic that was previously duplicated between
 * {@link com.empyrean.elide.config.SchemaMultiTenantConnectionProvider} (Hibernate ORM's path)
 * and {@link com.empyrean.elide.datastore.TenantAwareDataSource} (Elide's raw-JDBC aggregation
 * store path). Framework-agnostic - it only deals in {@link Connection} and {@link String}, so
 * it is equally usable from a Hibernate-typed caller and a plain-JDBC caller.
 */
@Component
public class SchemaTenancyStrategy implements TenancyStrategy {

    private final TenantInfo tenantInfo;

    public SchemaTenancyStrategy(TenantInfo tenantInfo) {
        this.tenantInfo = tenantInfo;
    }

    /**
     * Rejects anything outside the known tenant set (the default tenant, or a configured
     * key-protected tenant). This is the single chokepoint where a tenant identifier is turned
     * into an actual schema routing decision; converting an unknown identifier into a hard
     * failure here, rather than a silent fall-through, catches a future resolver/filter bug
     * before it can route a connection to an arbitrary schema.
     */
    @Override
    public void validateTenant(String tenantIdentifier) throws SQLException {
        if (!tenantInfo.getDefaultTenant().equals(tenantIdentifier)
                && !tenantInfo.getTenants().contains(tenantIdentifier)) {
            throw new SQLException("Unknown tenant: " + tenantIdentifier);
        }
    }

    @Override
    public void scopeConnection(Connection connection, String tenantIdentifier) throws SQLException {
        connection.setSchema(tenantIdentifier);
    }

    /**
     * Resets the connection's schema back to the default tenant's schema. Callers that pool and
     * reuse connections across requests/tenants (e.g. Hikari) must call this before a connection
     * returns to the pool - a schema setting that leaked past release would silently serve the
     * next borrower's queries against the wrong tenant's data.
     */
    @Override
    public void unscopeConnection(Connection connection) throws SQLException {
        connection.setSchema(tenantInfo.getDefaultTenant());
    }
}
