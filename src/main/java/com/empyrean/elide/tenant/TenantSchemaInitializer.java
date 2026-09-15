package com.empyrean.elide.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Creates each configured tenant's DB schema and {@code note} table at application startup.
 * <p>
 * Only covers the key-protected tenants from {@link TenantInfo#getTenants()} - the default
 * tenant's schema is not provisioned here because it must already exist before Hibernate ORM's
 * own boot-time {@code ddl-auto} runs against it, which happens earlier than any
 * {@link ApplicationRunner} can. The default tenant is therefore named after a schema that
 * already exists in a fresh database (see {@link TenantInfo#getDefaultTenant()}).
 * <p>
 * The table DDL mirrors exactly what Hibernate's {@code ddl-auto} generates for the
 * {@code Note} entity under Spring Boot's default physical naming strategy
 * (camelCase -> snake_case, unquoted -> lowercased under this project's H2
 * {@code DATABASE_TO_LOWER=TRUE} setting): table {@code note}, column {@code created_date}.
 * Using a different identifier here would leave the tenant schemas structurally different from
 * the default schema Hibernate provisions itself, which would break routed reads/writes for
 * those tenants even though this initializer "succeeded".
 * <p>
 * <b>Maintainers:</b> re-check this DDL against Hibernate's generated {@code public.note} table
 * whenever fields are ADDED to {@link com.empyrean.elide.model.Note}, and also whenever
 * {@code Note}'s VALIDATION ANNOTATIONS change ({@code @Size}, {@code @NotBlank},
 * {@code @NotNull}). {@code spring-boot-starter-validation} makes Hibernate's Bean Validation
 * DDL integration feed those annotations into column type and nullability (e.g. {@code @Size}
 * sets {@code varchar} length, {@code @NotBlank}/{@code @NotNull} sets {@code NOT NULL}), so a
 * validation-only change to the entity can silently desync this hand-written DDL from what
 * Hibernate actually generates for {@code public}, exactly as happened when this class's
 * {@code body}/{@code email} columns were first written without matching
 * {@code varchar(2000) NOT NULL} / {@code NOT NULL}.
 */
@Component
@Order(1)
public class TenantSchemaInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TenantSchemaInitializer.class);

    private final DataSource dataSource;
    private final TenantInfo tenantInfo;

    public TenantSchemaInitializer(DataSource dataSource, TenantInfo tenantInfo) {
        this.dataSource = dataSource;
        this.tenantInfo = tenantInfo;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String tenantId : tenantInfo.getTenants()) {
            provisionSchema(tenantId);
        }
    }

    private void provisionSchema(String tenantId) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + tenantId + "\"");
            statement.execute(createTableDdl(tenantId));
            LOG.info("Provisioned tenant schema: {}", tenantId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to provision tenant schema: " + tenantId, e);
        }
    }

    private String createTableDdl(String tenantId) {
        return """
                CREATE TABLE IF NOT EXISTS "%s".note (
                    id uuid NOT NULL,
                    body varchar(2000) NOT NULL,
                    email varchar(255) NOT NULL,
                    created_date timestamp(6),
                    PRIMARY KEY (id)
                )
                """.formatted(tenantId);
    }
}
