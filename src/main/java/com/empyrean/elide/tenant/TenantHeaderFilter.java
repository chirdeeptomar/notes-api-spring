package com.empyrean.elide.tenant;

import com.empyrean.elide.observability.QuerySourceRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves which tenant a JSON:API or GraphQL request belongs to from the {@code X-API-KEY}
 * header, and publishes it on {@link TenantContext} for the rest of the request.
 * <p>
 * A missing or blank header is rejected with {@code 401} ({@code "Missing X-API-KEY"}); a header
 * present but not mapped to any tenant is also rejected with {@code 401}
 * ({@code "Unknown X-API-KEY"}). There is no default/"public" tenant a request can fall back
 * into - every request must present a key matching one of {@link TenantInfo}'s configured
 * tenants. Only requests under the configured JSON:API path ({@code elide.json-api.path}) or
 * GraphQL path ({@code elide.graphql.path}) are affected - other endpoints (docs, metrics,
 * explorer UI) pass through untouched.
 * <p>
 * Registered only when {@code svc.tenancy.enabled=true} - multi-tenancy is opt-in, so this bean
 * does not exist by default. When disabled (the default), this bean is never created at all, so
 * no filter is ever added to the servlet chain - not merely a pass-through no-op - and every
 * request succeeds regardless of what {@code X-API-KEY} it carries or omits.
 * <p>
 * The {@code finally} clearing {@link TenantContext} is mandatory, not hygiene: servlet
 * threads are pooled, so a leaked tenant would serve the next request on that thread from the
 * wrong schema.
 * <p>
 * This filter alone is NOT sufficient to make the tenant visible where Elide does its work.
 * Elide's controllers return {@code Callable}, so Spring MVC dispatches the actual handling to
 * an async worker thread after {@code doFilter} has already returned and this filter's
 * {@code finally} has cleared the {@link ThreadLocal}.
 * {@link TenantAsyncConfiguration} re-establishes the tenant on that worker thread; both
 * mechanisms are required.
 */
@Component
@ConditionalOnProperty(name = "svc.tenancy.enabled", havingValue = "true", matchIfMissing = false)
public class TenantHeaderFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(TenantHeaderFilter.class);
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String JSON_API_MEDIA_TYPE = "application/vnd.api+json";

    private final TenantInfo tenantInfo;
    private final String jsonApiPath;
    private final String graphqlPath;

    public TenantHeaderFilter(
            TenantInfo tenantInfo,
            @Value("${elide.json-api.path:/api/v1}") String jsonApiPath,
            @Value("${elide.graphql.path:/graphql/api/v1}") String graphqlPath) {
        this.tenantInfo = tenantInfo;
        this.jsonApiPath = jsonApiPath;
        this.graphqlPath = graphqlPath;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // getRequestURI() includes the servlet context path, but the configured Elide paths are
        // relative to the context root. Under a non-root server.servlet.context-path the raw URI
        // would never match the configured prefix, the filter would become a no-op for EVERY
        // request, and the application would fail OPEN: no 401 for unknown keys and every tenant
        // silently served from the default schema. Strip the context path so the comparison is
        // against the same namespace the Elide properties are expressed in.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean isGraphql = matches(path, graphqlPath);
        if (!matches(path, jsonApiPath) && !isGraphql) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            reject(response, "Missing " + API_KEY_HEADER, isGraphql);
            return;
        }
        String tenantId = tenantInfo.getTenantForKey(apiKey);
        if (tenantId == null) {
            reject(response, "Unknown " + API_KEY_HEADER, isGraphql);
            return;
        }

        try {
            TenantContext.set(tenantId);
            // Record it on the request's tally too. The query-source summary is emitted from an
            // async callback, long after the finally below has cleared the ThreadLocal, so reading
            // the tenant at that point would report "none" for every request.
            QuerySourceRecorder recorder = QuerySourceRecorder.current();
            if (recorder != null) {
                recorder.setTenantId(tenantId);
            }
            LOG.debug("filter resolved tenant={} for api key", tenantId);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void reject(HttpServletResponse response, String detail, boolean isGraphql)
            throws IOException {
        String body = isGraphql
                ? """
                        {"errors":[{"message":"%s"}]}""".formatted(detail)
                : """
                        {"errors":[{"status":"401","title":"Unauthorized","detail":"%s"}]}"""
                        .formatted(detail);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(isGraphql ? "application/json" : JSON_API_MEDIA_TYPE);
        // Set before getWriter(): the encoding is fixed once the writer is obtained, and both
        // JSON media types are UTF-8 by specification.
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }

    /**
     * Segment-aware prefix match. A plain {@code startsWith} would treat {@code /api/v1extra}
     * and {@code /api/v1notes} as API paths and reject them with a 401 for an unknown key,
     * breaking the contract that everything outside the Elide endpoints passes through
     * untouched. Only the prefix itself, or the prefix followed by a path separator, counts.
     *
     * @param path   the context-relative request path
     * @param prefix the configured Elide endpoint path
     * @return whether {@code path} lies at or under {@code prefix}
     */
    private static boolean matches(String path, String prefix) {
        return path.equals(prefix)
                || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }
}
