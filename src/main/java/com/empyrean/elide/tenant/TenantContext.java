package com.empyrean.elide.tenant;

/**
 * Holds the tenant resolved for the current request, for the duration of that request.
 * <p>
 * A {@link ThreadLocal} is the storage, but a single request does NOT stay on one thread here.
 * Elide's Spring controllers return {@code Callable}, so Spring MVC hands the real work to an
 * async worker ({@code task-N}) thread while {@code TenantHeaderFilter} set the value on the
 * servlet ({@code http-nio-...-exec-N}) thread and cleared it as {@code doFilter} unwound.
 * {@code TenantAsyncConfiguration} is what makes the value visible to
 * {@code RequestTenantResolver} / {@code TenantAwareDataSource} on the worker thread; without
 * it they would read {@code null} and silently fall back to the default tenant.
 * <p>
 * Consequently, any code that reads this value must run on either the servlet thread or the
 * MVC-managed async worker for the same request. Work handed to any OTHER thread (an
 * {@code @Async} method, a manual {@code CompletableFuture}, a scheduled job) must capture
 * {@link #get()} on the calling thread and pass it explicitly - this is a plain
 * {@code ThreadLocal}, not an {@code InheritableThreadLocal}, and no context-propagation
 * library is in use.
 * <p>
 * Servlet container threads are pooled and reused, so {@link #clear()} in a {@code finally}
 * block is mandatory: a leaked value would silently serve the next request on that thread
 * from the wrong tenant's schema.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Sets the tenant for the current thread. */
    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /** @return the tenant for the current thread, or {@code null} if none is in scope. */
    public static String get() {
        return CURRENT_TENANT.get();
    }

    /** Removes the current thread's tenant. Must be called in a {@code finally} block. */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
