package com.empyrean.elide.model;

import com.empyrean.elide.hook.NoteAuditPreCommitHook;
import com.empyrean.elide.hook.NoteNormalizePreSecurityHook;
import com.empyrean.elide.hook.NotePostCommitHook;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.search.engine.backend.types.Searchable;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;

import java.util.Date;
import java.util.UUID;

/**
 * A single note, exposed as the {@code notes} JSON:API resource at
 * {@code /api/v1/notes}.
 * <p>
 * Rows are tenant-scoped: which DB schema a given request reads and writes
 * through is
 * decided by {@link com.empyrean.elide.tenant.RequestTenantResolver}, not by
 * any field on
 * this entity.
 */
@Entity
@Getter
@Include(name = "notes")
@Indexed
// Second-level cached, so a repeat read of /api/v1/notes/{id} is served from Infinispan rather
// than the database. Tenant-safe without extra work: Hibernate folds the current tenant into the
// cache key (DefaultCacheKeysFactory#createEntityKey), and TenantAwareJCacheRegionFactory
// additionally gives each tenant its own region so eviction budgets are not shared.
//
// Caching here is by ID only. Filtering on body routes to the Lucene index via SearchDataStore
// and never touches this cache; unfiltered list queries go to the database, because Hibernate's
// query cache is off (see hibernate.cache.use_query_cache in application.properties).
//
// CHOOSING A STRATEGY FOR A NEW ENTITY - this is the decision to copy, not the annotation:
//   READ_WRITE            mutable data read far more often than written (products, instruments).
//                         Soft locks mean a concurrent write never leaves a stale entry readable.
//   NONSTRICT_READ_WRITE  effectively static reference data (currencies, exchanges, calendars).
//                         Cheaper, but tolerates a brief stale window after a write.
//   (do not cache)        volatile data where staleness is a correctness bug rather than a
//                         latency trade - prices, positions, balances.
// TRANSACTIONAL is not an option here: it requires a JTA transaction manager this app does not
// configure.
//
// Note is READ_WRITE because it is mutable through both JSON:API and GraphQL.
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
// Normalize before security checks and validation run, so the stored value is the one that
// gets validated. Bound for CREATE and UPDATE separately: the annotation takes one operation.
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE,
        phase = LifeCycleHookBinding.TransactionPhase.PRESECURITY,
        hook = NoteNormalizePreSecurityHook.class, oncePerRequest = false)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE,
        phase = LifeCycleHookBinding.TransactionPhase.PRESECURITY,
        hook = NoteNormalizePreSecurityHook.class, oncePerRequest = false)
// Audit every write from inside the transaction. oncePerRequest = false is what populates the
// ChangeSpec: bound this way the hook fires once per *changed field* with the old and new
// values, whereas the default (true) fires once per entity with an empty ChangeSpec.
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE,
        phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
        hook = NoteAuditPreCommitHook.class, oncePerRequest = false)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE,
        phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
        hook = NoteAuditPreCommitHook.class, oncePerRequest = false)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE,
        phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
        hook = NoteAuditPreCommitHook.class, oncePerRequest = false)
// Side effects that must only happen once the write is durable. Left at the default
// oncePerRequest = true so a note produces exactly one event, not one per changed field.
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE,
        phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT,
        hook = NotePostCommitHook.class)
public class Note {

    /** Server-generated primary key; not settable by clients. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Free-text note content, indexed for full-text search via
     * elide-datastore-search.
     */
    @Setter
    @NotBlank
    // searchable=YES is required explicitly: elide-datastore-search's fieldIsIndexed() check
    // (SearchDataTransaction) tests searchable() == Searchable.YES, but @FullTextField's default
    // is Searchable.DEFAULT, which fails that check and silently routes every query on this field
    // to the plain JPA store instead of the Lucene index.
    @FullTextField(searchable = Searchable.YES)
    @Size(max = 2000, message = "Note body must be 2000 characters or less")
    private String body;

    /** Email address associated with the note's author. */
    @Setter
    @NotBlank
    @Email(message = "Email must be a valid email address")
    private String email;

    /** When the note was created; set once and never updated. */
    private Date createdDate;

    @PrePersist
    void onCreate() {
        createdDate = new Date();
    }
}
