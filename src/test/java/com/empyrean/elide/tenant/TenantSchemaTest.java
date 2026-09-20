package com.empyrean.elide.tenant;

import com.empyrean.elide.model.Note;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies TenantSchemaInitializer provisioned a schema per configured tenant at startup, that
 * {@link RequestTenantResolver} resolves the right identifier, and - the part neither of those
 * facts implies on its own - that Hibernate actually routes reads and writes to the schema
 * named by {@link TenantContext} rather than silently funnelling every tenant into one schema.
 */
@SpringBootTest
class TenantSchemaTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    TenantInfo tenantInfo;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void eachConfiguredTenantHasASchema() throws Exception {
        List<String> schemas = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT schema_name FROM information_schema.schemata")) {
            while (rs.next()) {
                schemas.add(rs.getString(1).toLowerCase());
            }
        }

        assertThat(schemas).contains("public");
        for (String tenant : tenantInfo.getTenants()) {
            assertThat(schemas).contains(tenant.toLowerCase());
        }
    }

    @Test
    void resolverFallsBackToThePhysicalDefaultSchemaOutsideARequest() {
        TenantContext.clear();
        RequestTenantResolver resolver = new RequestTenantResolver(tenantInfo);
        // No default/"public" tenant any more, but Hibernate's own boot-time work (e.g. the
        // mass-indexer, which runs before any tenant schema is provisioned) still needs a
        // schema that already exists - see RequestTenantResolver's javadoc.
        assertThat(resolver.resolveCurrentTenantIdentifier())
                .isEqualTo(SchemaTenancyStrategy.PHYSICAL_DEFAULT_SCHEMA);
    }

    @Test
    void resolverUsesTheTenantInScope() {
        try {
            TenantContext.set("tenant_a");
            RequestTenantResolver resolver = new RequestTenantResolver(tenantInfo);
            assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo("tenant_a");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The brief's other tests confirm the schemas exist and that the resolver returns the
     * right string - neither proves Hibernate is actually using that string to route
     * connections. A {@link SchemaMultiTenantConnectionProvider} that resolves the tenant but
     * never calls {@code Connection.setSchema(...)}, or that leaks the schema across pooled
     * connections, would pass both of those tests while silently writing every tenant's data
     * into one schema. This test proves real isolation end to end through Hibernate: a note
     * persisted under {@code tenant_a} is invisible from {@code tenant_b} and visible again
     * from {@code tenant_a}.
     */
    @Test
    void notesAreIsolatedPerTenantSchema() {
        UUID noteId;
        try {
            TenantContext.set("tenant_a");
            noteId = persistNote("isolated note " + UUID.randomUUID(), "isolation@example.com");
        } finally {
            TenantContext.clear();
        }

        try {
            TenantContext.set("tenant_b");
            assertThat(findNote(noteId)).isNull();
        } finally {
            TenantContext.clear();
        }

        try {
            TenantContext.set("tenant_a");
            Note reloaded = findNote(noteId);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getId()).isEqualTo(noteId);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID persistNote(String body, String email) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            Note note = new Note();
            note.setBody(body);
            note.setEmail(email);
            em.persist(note);
            em.getTransaction().commit();
            return note.getId();
        } finally {
            em.close();
        }
    }

    private Note findNote(UUID id) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.find(Note.class, id);
        } finally {
            em.close();
        }
    }

    /**
     * {@link TenantSchemaInitializer} hand-writes the {@code note} DDL for every key-protected
     * tenant schema instead of letting Hibernate generate it, because Hibernate's {@code
     * ddl-auto} only runs against the default {@code public} schema (see that class's javadoc).
     * Nothing enforces that the hand-written copy actually matches what Hibernate generated for
     * {@code public} - they are two independent sources of truth for the same table shape, and
     * they have drifted before ({@code body}/{@code email} were {@code varchar(255)} nullable
     * in the tenant schemas vs. Hibernate's Bean-Validation-derived {@code varchar(2000) NOT
     * NULL}). This test compares the actual {@code information_schema.columns} rows so any
     * future drift fails loudly instead of only surfacing as a value-too-long error on whichever
     * tenant happens to receive a long note body first.
     */
    @Test
    void tenantTablesMatchTheEntityDerivedPublicTable() throws Exception {
        Map<String, String> expected = columnsOf("public");
        assertThat(expected).isNotEmpty();
        for (String tenant : tenantInfo.getTenants()) {
            assertThat(columnsOf(tenant))
                    .as("tenant %s note table must match the entity-derived public table", tenant)
                    .isEqualTo(expected);
        }
    }

    private Map<String, String> columnsOf(String schema) throws Exception {
        Map<String, String> columns = new TreeMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT column_name, data_type, character_maximum_length, is_nullable
                     FROM information_schema.columns
                     WHERE UPPER(table_schema) = UPPER(?) AND UPPER(table_name) = 'NOTE'
                     """)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.put(rs.getString("column_name").toUpperCase(),
                            rs.getString("data_type") + "|" + rs.getString("character_maximum_length")
                                    + "|" + rs.getString("is_nullable"));
                }
            }
        }
        return columns;
    }
}
