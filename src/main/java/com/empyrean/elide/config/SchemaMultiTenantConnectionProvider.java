package com.empyrean.elide.config;

import com.empyrean.elide.tenant.TenantInfo;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Routes each unit of work's JDBC connection to its tenant's DB schema.
 * <p>
 * {@link com.empyrean.elide.tenant.RequestTenantResolver} only tells Hibernate <em>which</em>
 * tenant is current; on its own that resolves nothing about where a query actually runs. This
 * provider is what performs the routing: it hands out a connection scoped to the requested
 * tenant's schema via {@link Connection#setSchema(String)}, and - critically - resets that
 * schema back to the default before the connection returns to the Hikari pool. Connections are
 * pooled and reused across requests/tenants, so a schema setting that leaked past release would
 * silently serve the next borrower's queries against the wrong tenant's data. This is the same
 * class of bug as a leaked {@link com.empyrean.elide.tenant.TenantContext} ThreadLocal, just one
 * layer lower.
 * <p>
 * Registered with Hibernate by {@link HibernateTenancyConfiguration} under
 * {@code AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER}. Required in addition to the
 * {@code CurrentTenantIdentifierResolver}: Hibernate 7's {@code SCHEMA} multi-tenancy strategy
 * needs both a resolver (which tenant) and a connection provider (route to that tenant).
 */
@Component
public class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;
    private final String defaultSchema;

    public SchemaMultiTenantConnectionProvider(DataSource dataSource, TenantInfo tenantInfo) {
        this.dataSource = dataSource;
        this.defaultSchema = tenantInfo.getDefaultTenant();
    }

    /**
     * @return a connection with no tenant-specific schema applied, used by Hibernate for
     *         operations that are not scoped to a particular tenant (e.g. some schema tooling)
     */
    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setSchema(tenantIdentifier);
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        // Reset before returning to the Hikari pool - see class javadoc.
        connection.setSchema(defaultSchema);
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    /**
     * @return {@code true} - this provider already applies the tenant's schema in
     *         {@link #getConnection(String)}, so Hibernate must not also attempt to set it
     *         itself (the interface's default is {@code false}, meaning "Hibernate handles it")
     */
    @Override
    public boolean handlesConnectionSchema() {
        return true;
    }

    // MultiTenantConnectionProvider extends Service, Wrapped. Wrapped's two methods have no
    // default implementation in Hibernate 7 and must be implemented even though nothing in this
    // application unwraps this provider.

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return unwrapType.isInstance(this);
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        if (unwrapType.isInstance(this)) {
            return unwrapType.cast(this);
        }
        throw new UnsupportedOperationException(
                "Cannot unwrap SchemaMultiTenantConnectionProvider as " + unwrapType.getName());
    }
}
