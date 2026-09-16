package com.empyrean.elide.tenant;

import com.empyrean.elide.observability.QuerySourceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Callable;

/**
 * Carries the tenant resolved by {@link TenantHeaderFilter} across Spring MVC's async boundary,
 * onto the worker thread where the request is actually served.
 *
 * <h2>Why this is required for correctness, not convenience</h2>
 * Every {@code com.yahoo.elide.spring.controllers.JsonApiController} handler method
 * ({@code elideGet}, {@code elidePost}, {@code elidePatch}, {@code elideDelete},
 * {@code elideDeleteRelation}) and {@code GraphqlController.post} returns
 * {@link Callable}{@code <ResponseEntity<String>>}. Spring MVC treats a {@code Callable} return
 * value as a request for Servlet async processing: {@code WebAsyncManager.startCallableProcessing}
 * submits the task to the {@code applicationTaskExecutor} ({@code task-N} threads) and the
 * original servlet thread ({@code http-nio-...-exec-N}) unwinds the filter chain immediately.
 * The sequence is therefore:
 * <ol>
 *   <li>{@code TenantHeaderFilter} sets {@link TenantContext} on the servlet thread;</li>
 *   <li>async processing starts and {@code doFilter} <em>returns</em>, so the filter's
 *       {@code finally} clears the {@link ThreadLocal} - before Elide has done any work;</li>
 *   <li>Elide's {@code Callable} runs on a {@code task-N} thread where a plain
 *       {@code ThreadLocal} is invisible, so {@code TenantContext.get()} is {@code null} and
 *       {@link RequestTenantResolver} falls back to the {@code public} tenant.</li>
 * </ol>
 * Without this interceptor, tenant isolation holds only by accident: with
 * {@code spring.jpa.open-in-view=true} (the default) the {@code EntityManager}, and with it the
 * Hibernate {@code Session}'s already-bound tenant identifier, is opened on the servlet thread
 * and merely reused by the worker. Set {@code open-in-view=false} - a routine change Spring Boot
 * warns about at startup - and every tenant's traffic silently routes to {@code public}: no
 * exception, no log, just cross-tenant data exposure. Anything else that reads the tenant on
 * the thread running the work (a tenant-aware {@code DataSource}, a search or analytics store)
 * has the same exposure.
 *
 * <h2>Clearing</h2>
 * Task-executor threads are pooled exactly like servlet threads, so a value left behind would
 * be picked up by an unrelated later request. {@code postProcess} runs on the worker thread
 * immediately after the {@code Callable} returns or throws, which is where the clear must
 * happen. {@code handleTimeout} / {@code handleError} / {@code afterCompletion} run on the
 * container thread rather than the worker, but they clear too: the servlet thread's own value
 * may still be set there (the timeout/error notification can arrive on a pooled thread of
 * either kind), and clearing an already-empty {@link ThreadLocal} is harmless.
 * <p>
 * This does <em>not</em> replace {@code TenantHeaderFilter}'s {@code try/finally} - that still
 * owns the servlet thread's value, including for synchronous handlers that never go async.
 */
@Configuration
public class TenantAsyncConfiguration implements WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(TenantAsyncConfiguration.class);

    /**
     * Request-scoped attribute carrying the tenant across the thread handoff. A request
     * attribute is the right vehicle because the {@code HttpServletRequest} is shared by both
     * threads, whereas the {@link ThreadLocal} by definition is not.
     */
    static final String TENANT_ATTRIBUTE = TenantAsyncConfiguration.class.getName() + ".TENANT";

    /**
     * Carries the per-request {@link QuerySourceRecorder} across the same hand-off, for the same
     * reason and by the same mechanism as the tenant. Without this the cache, index and database
     * counters would all be recorded into a recorder that no longer exists on the worker thread,
     * and every Elide request - which is to say every request that touches data - would log an
     * empty tally while looking perfectly healthy.
     * <p>
     * Unlike the tenant, the recorder is <em>not</em> cleared in {@code postProcess}: the filter's
     * async listener reads it after the callable returns. It is cleared in
     * {@code afterCompletion}, once the summary has been emitted.
     */
    static final String RECORDER_ATTRIBUTE = TenantAsyncConfiguration.class.getName() + ".RECORDER";

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.registerCallableInterceptors(new TenantPropagatingInterceptor());
    }

    static final class TenantPropagatingInterceptor implements CallableProcessingInterceptor {

        /** Runs on the servlet thread, while the filter's tenant is still in scope. */
        @Override
        public <T> void beforeConcurrentHandling(NativeWebRequest request, Callable<T> task) {
            String tenantId = TenantContext.get();
            request.setAttribute(TENANT_ATTRIBUTE, tenantId, RequestAttributes.SCOPE_REQUEST);
            request.setAttribute(RECORDER_ATTRIBUTE, QuerySourceRecorder.current(),
                    RequestAttributes.SCOPE_REQUEST);
            LOG.debug("captured tenant={} on {} for async handoff",
                    tenantId, Thread.currentThread().getName());
        }

        /** Runs on the async worker thread, immediately before the {@code Callable}. */
        @Override
        public <T> void preProcess(NativeWebRequest request, Callable<T> task) {
            QuerySourceRecorder.bind((QuerySourceRecorder) request.getAttribute(
                    RECORDER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));

            Object tenantId = request.getAttribute(TENANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (tenantId == null) {
                // No tenant was in scope on the servlet thread (a non-API path, or a handler
                // reached without the filter). Clear rather than leave whatever this pooled
                // worker happened to hold from an earlier request.
                TenantContext.clear();
                return;
            }
            TenantContext.set((String) tenantId);
            LOG.debug("restored tenant={} on async worker {}",
                    tenantId, Thread.currentThread().getName());
        }

        /** Runs on the async worker thread after the {@code Callable} returns or throws. */
        @Override
        public <T> void postProcess(NativeWebRequest request, Callable<T> task, Object result) {
            TenantContext.clear();
            // Unbind from this pooled worker thread, but leave the instance on the request
            // attribute: the filter's async listener still has to read the finished tally.
            QuerySourceRecorder.clear();
        }

        @Override
        public <T> Object handleTimeout(NativeWebRequest request, Callable<T> task) {
            TenantContext.clear();
            QuerySourceRecorder.clear();
            return RESULT_NONE;
        }

        @Override
        public <T> Object handleError(NativeWebRequest request, Callable<T> task, Throwable t) {
            TenantContext.clear();
            QuerySourceRecorder.clear();
            return RESULT_NONE;
        }

        @Override
        public <T> void afterCompletion(NativeWebRequest request, Callable<T> task) {
            request.removeAttribute(TENANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            request.removeAttribute(RECORDER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            TenantContext.clear();
            QuerySourceRecorder.clear();
        }
    }
}
