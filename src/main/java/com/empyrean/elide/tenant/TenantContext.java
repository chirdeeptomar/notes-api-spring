package com.empyrean.elide.tenant;

/**
 * Holds the tenant resolved for the current request, for the duration of that request.
 * <p>
 * A {@link ThreadLocal} is correct here because Spring MVC is servlet-based and blocking:
 * {@code TenantHeaderFilter} (which sets the value) and
 * {@code RequestTenantResolver} / {@code TenantAwareDataSource} (which read it) all run on the
 * same request thread. The Quarkus version of this app had to store the tenant on a Vert.x
 * {@code RoutingContext} instead, because its filter ran on an I/O thread before the
 * request-scoped context existed - that constraint does not apply here.
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
