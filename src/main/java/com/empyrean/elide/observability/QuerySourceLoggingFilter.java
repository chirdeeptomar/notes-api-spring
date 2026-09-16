package com.empyrean.elide.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Emits one line per API request saying which tier served it - cache, index, or database.
 * <p>
 * <b>Why the line is emitted here rather than at each tier.</b> See {@link QuerySourceRecorder}:
 * one request commonly touches several tiers, and the useful statement is the combination, not
 * each part in isolation. A read served entirely from Infinispan and one that missed the cache and
 * fell through to SQL differ only in the tally, so the tally is what gets logged.
 * <p>
 * <b>Ordering.</b> This runs at {@link Ordered#HIGHEST_PRECEDENCE} so the recorder is installed
 * before anything else can touch data, and so the summary is emitted after the whole chain has
 * unwound. It does not depend on {@code TenantHeaderFilter}'s order either: that filter records
 * the tenant onto the tally as it resolves it, and a request rejected for an unknown API key
 * simply has none - reported as {@code none}, which is itself worth being able to see.
 * <p>
 * <b>The async caveat, which is the whole difficulty.</b> Elide's controllers return
 * {@code Callable}, so the data access happens on a worker thread <em>after</em> this filter's
 * {@code finally} has already run. A naive implementation logs an empty tally for every Elide
 * request and a correct one only for plain controllers - it looks like it works, and the caching
 * it is meant to observe is invisible precisely where it matters most. The recorder is therefore
 * propagated onto the worker thread by {@link com.empyrean.elide.tenant.TenantAsyncConfiguration},
 * alongside the tenant, and the summary is emitted from the async completion callback rather than
 * from {@code finally}. Requests that never went async are logged directly.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuerySourceLoggingFilter extends OncePerRequestFilter {

    /**
     * The last completed request's tally, kept solely so tests can assert on what was reported.
     * <p>
     * A test cannot reach the recorder any other way: it lives in a {@link ThreadLocal} on an
     * Elide worker thread that the test thread never touches, and it is cleared before the test
     * regains control. The alternative - asserting on captured log output - would pin the log's
     * wording rather than its correctness, and would keep passing if the numbers in it went wrong.
     * <p>
     * Volatile because it is written on a container thread and read from the test thread. It is
     * deliberately not used by anything in production.
     */
    private static volatile QuerySourceRecorder lastCompleted;

    /**
     * @return the tally from the most recently completed API request, for tests only
     */
    public static QuerySourceRecorder lastCompleted() {
        return lastCompleted;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        QuerySourceRecorder recorder = QuerySourceRecorder.begin();
        try {
            filterChain.doFilter(request, response);
        } finally {
            QuerySourceRecorder.clear();
            if (request.isAsyncStarted()) {
                // The work has not happened yet; log once the async dispatch completes, by which
                // point the worker thread has finished recording into this same instance.
                request.getAsyncContext().addListener(
                        new QuerySourceSummaryListener(recorder, request, response));
            } else {
                log(recorder, request, response.getStatus());
            }
        }
    }

    /**
     * Only the data-bearing endpoints are worth a line. Static resources, the docs UI and actuator
     * probes would otherwise dominate the log with empty tallies.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.startsWith("/api/") || path.startsWith("/graphql/"));
    }

    static void log(QuerySourceRecorder recorder, HttpServletRequest request, int status) {
        if (recorder == null) {
            return;
        }
        lastCompleted = recorder;
        log.info("{} {} tenant={} status={} servedBy={} {}",
                request.getMethod(),
                request.getRequestURI(),
                tenantLabel(recorder),
                status,
                servedBy(recorder),
                recorder.summary());
    }

    /**
     * The tenant as it appears on the log line.
     * <p>
     * Taken from the tally rather than {@code TenantContext}, because this runs on a container
     * thread after the request's {@code ThreadLocal} has been cleared - reading the context here
     * reported {@code none} for every request, including correctly authenticated ones. Kept as its
     * own method so a test can assert on the label the log actually prints; asserting on
     * {@code recorder.getTenantId()} instead would have passed against that bug, since the tally
     * was right and only its use here was wrong.
     */
    static String tenantLabel(QuerySourceRecorder recorder) {
        String tenant = recorder.getTenantId();
        return tenant == null ? "none" : tenant;
    }

    /**
     * Collapses the tally into the single word a reader scans for: which tier <em>decided</em> the
     * result.
     * <p>
     * The index is reported ahead of the database deliberately. An index-served read still issues
     * SQL - Lucene selects the ids and Hibernate fetches those rows by primary key - so ranking by
     * "did any SQL run" would label every full-text query {@code database} and make the index
     * invisible in exactly the logs meant to show it. What distinguishes the tiers is which one
     * did the selecting, so that is what this reports; the counts in the same line still show the
     * SQL that followed.
     */
    static String servedBy(QuerySourceRecorder recorder) {
        if (recorder.isEmpty()) {
            return "nothing";
        }
        if (recorder.getIndexQueries() > 0) {
            return "index";
        }
        if (recorder.getDatabaseLoads() > 0) {
            return recorder.getCacheHits() > 0 ? "database+cache" : "database";
        }
        if (recorder.getCacheHits() > 0) {
            return "cache";
        }
        // Reached by writes: a POST or PATCH consults the cache (recording a miss) and then
        // performs SQL that is not a SELECT, so nothing above matches. Falling through to "cache"
        // here would report a write that served nothing from cache as cache-served - the one
        // reading a person is most likely to act on, and the most wrong.
        return "none";
    }
}
