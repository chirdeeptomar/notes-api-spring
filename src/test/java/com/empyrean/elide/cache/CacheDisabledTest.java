package com.empyrean.elide.cache;

import com.empyrean.elide.model.Note;
import com.empyrean.elide.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code svc.cache.enabled=false} actually turns the second-level cache off, rather
 * than leaving it on with an empty configuration.
 * <p>
 * Worth its own context because the disabled branch is the one that runs when someone is
 * diagnosing a suspected cache problem in production - if the switch silently did nothing, it
 * would send them chasing the wrong cause.
 */
@SpringBootTest(properties = {
        "svc.cache.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class CacheDisabledTest {

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void repeatedReadsGoToTheDatabaseWhenCachingIsDisabled() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.clear();

        UUID id;
        try {
            TenantContext.set("tenant_a");
            id = persistNote("cache disabled " + UUID.randomUUID());
            findInFreshSession(id);
            findInFreshSession(id);
        } finally {
            TenantContext.clear();
        }

        assertThat(statistics.getSecondLevelCacheHitCount())
                .as("nothing should be served from a second-level cache that is switched off")
                .isZero();
        assertThat(statistics.getSecondLevelCachePutCount())
                .as("nothing should be written to a second-level cache that is switched off")
                .isZero();
    }

    private UUID persistNote(String body) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            Note note = new Note();
            note.setBody(body);
            note.setEmail("disabled@example.com");
            em.persist(note);
            em.getTransaction().commit();
            return note.getId();
        } finally {
            em.close();
        }
    }

    private Note findInFreshSession(UUID id) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.find(Note.class, id);
        } finally {
            em.close();
        }
    }
}
