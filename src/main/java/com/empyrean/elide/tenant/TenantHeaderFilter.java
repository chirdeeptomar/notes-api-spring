package com.empyrean.elide.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves which tenant a JSON:API or GraphQL request belongs to from the {@code X-API-KEY}
 * header, and publishes it on {@link TenantContext} for the rest of the request.
 * <p>
 * A missing header resolves to {@link TenantInfo#getDefaultTenant()}; a header present but not
 * mapped to any tenant is rejected with {@code 401}. Only requests under the configured
 * JSON:API path ({@code elide.json-api.path}) or GraphQL path ({@code elide.graphql.path}) are
 * affected - other endpoints (docs, metrics, explorer UI) pass through untouched.
 * <p>
 * The {@code finally} clearing {@link TenantContext} is mandatory, not hygiene: servlet
 * threads are pooled, so a leaked tenant would serve the next request on that thread from the
 * wrong schema.
 */
@Component
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

        String path = request.getRequestURI();
        boolean isGraphql = path.startsWith(graphqlPath);
        if (!path.startsWith(jsonApiPath) && !isGraphql) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        String tenantId;
        if (apiKey == null || apiKey.isBlank()) {
            tenantId = tenantInfo.getDefaultTenant();
        } else {
            tenantId = tenantInfo.getTenantForKey(apiKey);
            if (tenantId == null) {
                reject(response, "Unknown " + API_KEY_HEADER, isGraphql);
                return;
            }
        }

        try {
            TenantContext.set(tenantId);
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
        response.getWriter().write(body);
    }
}
