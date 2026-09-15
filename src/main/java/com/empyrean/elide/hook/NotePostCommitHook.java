package com.empyrean.elide.hook;

import com.empyrean.elide.model.Note;
import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reacts to a note that has been durably committed — the place for side effects that must not
 * happen for a write that later rolls back (publishing an event, sending a notification,
 * invalidating a cache).
 * <p>
 * POSTCOMMIT runs after the transaction commits, so the entity's server-generated id is
 * populated. Throwing from here cannot roll the write back: the data is already committed, so
 * failures must be handled locally rather than propagated as request failures.
 */
@Component
public class NotePostCommitHook implements LifeCycleHook<Note> {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(NotePostCommitHook.class);

    private final HookInvocationRecorder recorder;

    NotePostCommitHook(HookInvocationRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void execute(Operation operation, TransactionPhase phase, Note note,
                        RequestScope requestScope, Optional<ChangeSpec> changes) {
        LOG.info("COMMITTED {} note id={} - would publish a domain event here", operation, note.getId());

        if (recorder != null) {
            recorder.record("NotePostCommitHook", "id=" + note.getId());
        }
    }
}
