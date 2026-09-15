package com.empyrean.elide.hook;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory record of recent lifecycle hook firings, used to make the sample hooks
 * observable: by the tests, and by hand during development.
 * <p>
 * Bounded to {@link #MAX_INVOCATIONS} entries so a long-running process cannot grow it
 * without limit.
 */
@Component
public class HookInvocationRecorder {

    private static final int MAX_INVOCATIONS = 100;

    /** One recorded hook firing: which hook, and a short human-readable detail. */
    public record Invocation(String hookName, String detail) {
    }

    private final List<Invocation> invocations = new CopyOnWriteArrayList<>();

    public void record(String hookName, String detail) {
        while (invocations.size() >= MAX_INVOCATIONS && !invocations.isEmpty()) {
            invocations.remove(0);
        }
        invocations.add(new Invocation(hookName, detail));
    }

    public List<Invocation> getInvocations() {
        return List.copyOf(invocations);
    }

    public void clear() {
        invocations.clear();
    }
}
