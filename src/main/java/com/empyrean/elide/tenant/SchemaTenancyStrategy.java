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

    /**
     * H2's own physical default schema - NOT a tenant. There is no default/"public" tenant any
     * more (see {@link TenantInfo}), but this one physical schema is still needed for two
     * distinct reasons: (1) a connection released back to the pool needs a defined,
     * always-present schema to sit at, exactly as any other unscoped JDBC connection would - see
     * {@link #unscopeConnection}; (2) it is the one identifier {@link RequestTenantResolver} can
     * safely hand Hibernate for its own boot-time internal work (e.g. Hibernate Search's
     * mass-indexer, which runs during {@code Elide} bean construction, before
     * {@code TenantSchemaInitializer}'s {@code ApplicationRunner} phase has provisioned any real
     * tenant's schema) - see that class's javadoc. {@link #validateTenant} accepts this one
     * physical identifier alongside real tenants for exactly that second reason; it is still not
     * a tenant, and nothing in {@link TenantInfo#getTenants()} names it.
     * <p>
     * Lowercase, not {@code "PUBLIC"}: this application's H2 JDBC URL sets
     * {@code DATABASE_TO_LOWER=TRUE}, so H2 stores and reports its default schema as lowercase
     * {@code public} - {@code Connection.setSchema("PUBLIC")} against that database fails with
     * {@code "Schema \"PUBLIC\" not found"}, confirmed directly against a live H2 connection
     * before relying on this constant.
     */
    static final String PHYSICAL_DEFAULT_SCHEMA = "public";

    private final TenantInfo tenantInfo;

    public SchemaTenancyStrategy(TenantInfo tenantInfo) {
        this.tenantInfo = tenantInfo;
    }

    /**
     * Rejects anything outside the configured tenant set, with one exception:
     * {@link #PHYSICAL_DEFAULT_SCHEMA} is always accepted, since it is the identifier
     * {@link RequestTenantResolver} falls back to for Hibernate's own non-request-scoped work -
     * see that constant's javadoc. This is the single chokepoint where a tenant identifier is
     * turned into an actual schema routing decision; converting anything else unknown into a
     * hard failure here, rather than a silent fall-through, catches a future resolver/filter bug
     * before it can route a connection to an arbitrary schema.
     */
    @Override
    public void validateTenant(String tenantIdentifier) throws SQLException {
        if (!PHYSICAL_DEFAULT_SCHEMA.equals(tenantIdentifier)
                && !tenantInfo.getTenants().contains(tenantIdentifier)) {
            throw new SQLException("Unknown tenant: " + tenantIdentifier);
        }
    }

    @Override
    public void scopeConnection(Connection connection, String tenantIdentifier) throws SQLException {
        connection.setSchema(tenantIdentifier);
    }

    /**
     * Resets the connection's schema to {@link #PHYSICAL_DEFAULT_SCHEMA} - deliberately not a
     * tenant's schema - before it returns to the pool. Callers that pool and reuse connections
     * across requests/tenants (e.g. Hikari) must call this before a connection returns to the
     * pool - a schema setting that leaked past release would silently serve the next borrower's
     * queries against the wrong tenant's data.
     */
    @Override
    public void unscopeConnection(Connection connection) throws SQLException {
        connection.setSchema(PHYSICAL_DEFAULT_SCHEMA);
    }
}
