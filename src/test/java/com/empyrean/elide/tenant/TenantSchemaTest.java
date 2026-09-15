package com.empyrean.elide.tenant;

import com.empyrean.elide.model.Note;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies TenantSchemaInitializer provisioned a schema per configured tenant at startup, that
 * {@link RequestTenantResolver} resolves the right identifier, and - the part neither of those
 * facts implies on its own - that Hibernate actually routes reads and writes to the schema
 * named by {@link TenantContext} rather than silently funnelling every tenant into one schema.
 * <p>
 * {@code hibernate.search.backend.directory.type=local-heap} scopes this test's Lucene index to
 * memory instead of the project-root {@code ./Note} filesystem directory that the default
 * (unset) production configuration uses. Without it, this class's {@code @SpringBootTest}
 * context and {@code NoteRestCrudTest}'s each try to lock that same fixed path, and whichever
 * boots second fails with a Lucene {@code LockObtainFailedException} - a pre-existing gap in
 * the app's Hibernate Search test configuration that this class is the first to expose, since
 * it is the first second distinct {@code @SpringBootTest} context shape in the suite.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap")
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
    void resolverFallsBackToDefaultTenantOutsideARequest() {
        TenantContext.clear();
        RequestTenantResolver resolver = new RequestTenantResolver(tenantInfo);
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo("public");
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
}
