package com.empyrean.elide.tenant;

import com.empyrean.elide.config.TenancyProperties;
import com.empyrean.elide.model.Note;
import net.datafaker.Faker;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.SessionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds mock {@link Note} rows at startup, for local development only - every tenant's schema
 * when {@code svc.tenancy.enabled=true}, or the single implicit schema when it is false. Seeding
 * happens either way: {@code dev} convenience data is independent of whether tenancy happens to
 * be on, the same way caching and search stay independent axes (see
 * {@link com.empyrean.elide.config.CacheProperties}, {@link com.empyrean.elide.config.SearchProperties}).
 * <p>
 * Ordered after {@link TenantSchemaInitializer} so tenant schemas exist before anything is
 * written to them, when tenancy is on. There is no in-flight HTTP request at startup for
 * {@link RequestTenantResolver} to resolve a tenant from (and, when tenancy is disabled, that
 * resolver bean does not even exist - see its own javadoc), so both branches below open a
 * {@link Session} directly through {@link SessionFactory#withOptions()} rather than relying on
 * request-scoped tenant resolution.
 * <p>
 * <b>Enabled:</b> one session per entry in {@link TenantInfo#getTenants()}, each with an
 * explicit {@code tenantIdentifier(tenantId)} - a real, non-null tenant name Hibernate Search's
 * multi-tenancy strategy (itself in {@code MULTI_TENANCY} mode here) expects.
 * <p>
 * <b>Disabled:</b> exactly one session, with no explicit tenant identifier at all - not
 * {@code tenantIdentifier(null)}, but the plain {@code openSession()} the rest of the disabled
 * path also uses. Passing any tenant identifier here, even a real configured one, previously
 * failed every seed with {@code HSEARCH600031} ("invalid tenant identifiers... multi-tenancy is
 * disabled for this backend"): Hibernate Search had correctly determined single-tenancy mode
 * from the withheld ORM multi-tenancy SPI, and an explicit non-null tenant identifier on the
 * session contradicts that regardless of whether the tenant name itself was real or configured.
 */
@Component
@Order(2)
@Profile("dev")
public class MockNoteSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MockNoteSeeder.class);
    private static final int NOTES_PER_TENANT = 10;

    private final SessionFactory sessionFactory;
    private final TenantInfo tenantInfo;
    private final TenancyProperties tenancyProperties;

    public MockNoteSeeder(SessionFactory sessionFactory, TenantInfo tenantInfo,
            TenancyProperties tenancyProperties) {
        this.sessionFactory = sessionFactory;
        this.tenantInfo = tenantInfo;
        this.tenancyProperties = tenancyProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Faker faker = new Faker();
        if (!tenancyProperties.isEnabled()) {
            seed(sessionFactory.withOptions(), "the single implicit schema", faker);
            return;
        }
        for (String tenantId : tenantInfo.getTenants()) {
            seed(sessionFactory.withOptions().tenantIdentifier((Object) tenantId), "tenant: " + tenantId, faker);
        }
    }

    private void seed(SessionBuilder sessionBuilder, String description, Faker faker) {
        try (Session session = sessionBuilder.openSession()) {
            session.beginTransaction();
            for (int i = 0; i < NOTES_PER_TENANT; i++) {
                Note note = new Note();
                note.setBody(faker.lorem().sentence());
                note.setEmail(faker.internet().emailAddress());
                session.persist(note);
            }
            session.getTransaction().commit();
            LOG.info("Seeded {} mock notes into {}", NOTES_PER_TENANT, description);
        }
    }
}
