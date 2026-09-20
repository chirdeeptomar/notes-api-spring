package com.empyrean.elide.datastore;

import com.empyrean.elide.tenant.TenancyStrategy;
import com.empyrean.elide.tenant.TenantContext;
import com.empyrean.elide.tenant.TenantInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Wraps the application's default {@link DataSource} so every JDBC connection handed to Elide's
 * {@code SQLQueryEngine} is scoped to the current request's tenant schema, the same way Hibernate
 * ORM's schema-per-tenant multi-tenancy is scoped by
 * {@link com.empyrean.elide.tenant.TenantContext}.
 * <p>
 * The aggregation store talks to the database over plain JDBC, not Hibernate, so it has no way to
 * participate in {@code RequestTenantResolver} on its own — this class is what makes analytic
 * queries (e.g. {@code noteStats}) honor the {@code X-API-KEY} tenant instead of always reading
 * one fixed schema.
 * <p>
 * Scoping itself is delegated to the injected {@link TenancyStrategy} - the same one
 * {@code SchemaMultiTenantConnectionProvider} uses for Hibernate ORM's path - so both call sites
 * agree on how a tenant identifier becomes an actual schema. This class deliberately does
 * <b>not</b> call {@link TenancyStrategy#validateTenant} or
 * {@link TenancyStrategy#unscopeConnection}, unlike {@code SchemaMultiTenantConnectionProvider}:
 * it never validates an unknown tenant identifier, and it never explicitly resets a connection's
 * scope before returning it to the caller. This is an existing asymmetry between the two call
 * sites, preserved here as-is rather than unified - see {@link TenancyStrategy}'s javadoc.
 */
@Slf4j
public class TenantAwareDataSource implements DataSource {

    /** Bean name of the raw pooled DataSource Spring Boot autoconfigures. */
    private static final String POOLED_DATA_SOURCE_BEAN = "dataSource";

    private final BeanFactory beanFactory;
    private final TenantInfo tenantInfo;
    private final TenancyStrategy tenancyStrategy;
    private volatile DataSource delegate;

    public TenantAwareDataSource(BeanFactory beanFactory, TenantInfo tenantInfo,
            TenancyStrategy tenancyStrategy) {
        this.beanFactory = beanFactory;
        this.tenantInfo = tenantInfo;
        this.tenancyStrategy = tenancyStrategy;
    }

    /**
     * Resolves the raw pooled {@link DataSource} lazily and by name, rather than taking one as a
     * constructor parameter. Taking one would make this bean depend on a {@code DataSource} while
     * itself being a {@code DataSource}, which Spring resolves against this very bean - see
     * {@link com.empyrean.elide.config.ElideStoreConfiguration}'s class javadoc.
     */
    private DataSource delegate() {
        DataSource resolved = delegate;
        if (resolved == null) {
            synchronized (this) {
                resolved = delegate;
                if (resolved == null) {
                    resolved = beanFactory.getBean(POOLED_DATA_SOURCE_BEAN, DataSource.class);
                    delegate = resolved;
                }
            }
        }
        return resolved;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTenantSchema(delegate().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTenantSchema(delegate().getConnection(username, password));
    }

    private Connection withTenantSchema(Connection connection) throws SQLException {
        tenancyStrategy.scopeConnection(connection, resolveTenantId());
        return connection;
    }

    private String resolveTenantId() {
        String tenantId = TenantContext.get();
        String resolved = tenantId != null ? tenantId : tenantInfo.getDefaultTenant();
        log.debug("resolveTenantId(): tenantContext tenantId={} -> resolved={}", tenantId, resolved);
        return resolved;
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate().getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate().isWrapperFor(iface);
    }
}
