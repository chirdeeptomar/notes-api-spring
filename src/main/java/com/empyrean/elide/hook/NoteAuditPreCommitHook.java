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
 * Audits every write to a note, inside the transaction that performs it.
 * <p>
 * PRECOMMIT runs after security checks pass but before the transaction commits, so throwing
 * from here still rolls the write back — which is what makes it the right phase for an audit
 * that must not be bypassed. The {@link ChangeSpec} is present for UPDATE and carries the
 * old and new field values; it is empty for CREATE and DELETE.
 * <p>
 * Bound with {@code oncePerRequest = false} so it fires once per affected note rather than
 * once per request; the default ({@code true}) would log only the first note in a bulk write.
 */
@Component
public class NoteAuditPreCommitHook implements LifeCycleHook<Note> {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(NoteAuditPreCommitHook.class);

    /**
     * Populated by Spring: elide-spring-boot-autoconfigure's Injector delegates to the
     * ApplicationContext's AutowireCapableBeanFactory, so constructor injection on a hook is
     * resolved normally.
     */
    private final HookInvocationRecorder recorder;

    NoteAuditPreCommitHook(HookInvocationRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void execute(Operation operation, TransactionPhase phase, Note note,
                        RequestScope requestScope, Optional<ChangeSpec> changes) {
        String detail = changes
                .map(c -> "%s: '%s' -> '%s'".formatted(c.getFieldName(), c.getOriginal(), c.getModified()))
                .orElse("no field-level changes");

        LOG.info("AUDIT {} note id={} ({})", operation, note.getId(), detail);

        if (recorder != null) {
            recorder.record("NoteAuditPreCommitHook", detail);
        }
    }
}
