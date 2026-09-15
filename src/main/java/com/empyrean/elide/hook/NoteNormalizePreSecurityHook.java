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
 * Collapses runs of whitespace in a note's body and trims the result, before Elide runs
 * security checks or Hibernate runs Bean Validation.
 * <p>
 * PRESECURITY is the only phase where mutating the entity still affects what gets validated
 * and persisted: by PRECOMMIT the security checks have already run against the un-normalized
 * value, and by POSTCOMMIT the transaction is closed. Note that normalizing here means a body
 * of only whitespace becomes empty and is then rejected by {@code @NotBlank}, rather than
 * being silently stored.
 */
@Component
public class NoteNormalizePreSecurityHook implements LifeCycleHook<Note> {

    @Override
    public void execute(Operation operation, TransactionPhase phase, Note note,
                        RequestScope requestScope, Optional<ChangeSpec> changes) {
        String body = note.getBody();
        if (body != null) {
            note.setBody(body.trim().replaceAll("\\s+", " "));
        }
    }
}
