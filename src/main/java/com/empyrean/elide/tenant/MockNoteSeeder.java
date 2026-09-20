package com.empyrean.elide.tenant;

import com.empyrean.elide.model.Note;
import net.datafaker.Faker;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds each tenant's schema with mock {@link Note} rows at startup, for local development only.
 * <p>
 * Ordered after {@link TenantSchemaInitializer} so the tenant schemas exist before anything is
 * written to them. Every entry from {@link TenantInfo#getTenants()} is seeded through an
 * explicitly tenant-scoped Hibernate {@link Session}, since there is no in-flight HTTP request
 * for {@link RequestTenantResolver} to resolve a tenant from at startup time.
 */
@Component
@Order(2)
@Profile("dev")
public class MockNoteSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MockNoteSeeder.class);
    private static final int NOTES_PER_TENANT = 10;

    private final SessionFactory sessionFactory;
    private final TenantInfo tenantInfo;

    public MockNoteSeeder(SessionFactory sessionFactory, TenantInfo tenantInfo) {
        this.sessionFactory = sessionFactory;
        this.tenantInfo = tenantInfo;
    }

    @Override
    public void run(ApplicationArguments args) {
        Faker faker = new Faker();
        for (String tenantId : tenantInfo.getTenants()) {
            seedTenant(tenantId, faker);
        }
    }

    private void seedTenant(String tenantId, Faker faker) {
        try (Session session = sessionFactory.withOptions()
                .tenantIdentifier((Object) tenantId)
                .openSession()) {
            session.beginTransaction();
            for (int i = 0; i < NOTES_PER_TENANT; i++) {
                Note note = new Note();
                note.setBody(faker.lorem().sentence());
                note.setEmail(faker.internet().emailAddress());
                session.persist(note);
            }
            session.getTransaction().commit();
            LOG.info("Seeded {} mock notes into tenant: {}", NOTES_PER_TENANT, tenantId);
        }
    }
}
