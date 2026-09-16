package com.empyrean.elide.observability;

/**
 * Records, per request, which tier actually answered: the Infinispan second-level cache, the
 * Lucene index, or the database.
 * <p>
 * <b>Why a recorder rather than a log statement in each tier.</b> The three tiers are reached
 * through three unrelated mechanisms - Hibernate's {@code DomainDataStorageAccess} for the cache,
 * Elide's {@code SearchDataStore} for the index, and a JDBC statement for the database - and a
 * single read can touch more than one of them (an index hit still loads the matching entities,
 * which may themselves come from cache or database). Logging independently in each place produces
 * lines that have to be correlated by thread and timestamp to answer the only question worth
 * asking, which is "where did this request's data come from". Accumulating into one per-request
 * tally and emitting a single line at the end answers it directly.
 * <p>
 * <b>Why a ThreadLocal, and why it must be cleared.</b> The tiers have no shared call argument to
 * thread a collector through - {@code getFromCache} receives only a key and a Hibernate session,
 * and the JDBC layer sees neither. A ThreadLocal is the same mechanism {@link
 * com.empyrean.elide.tenant.TenantContext} already uses for the tenant, for the same reason.
 * Servlet threads are pooled, so {@link #clear()} in a {@code finally} is mandatory: a leaked
 * tally would be attributed to whatever request next ran on that thread.
 * <p>
 * <b>Async dispatch.</b> Elide's controllers return {@code Callable}, so the work happens on a
 * worker thread after the servlet filter has returned. The tally therefore accumulates on the
 * worker thread, and {@link com.empyrean.elide.tenant.TenantAsyncConfiguration} - which already
 * bridges the tenant across that boundary - is what carries the recorder across too. This is the
 * same two-mechanism arrangement documented on {@code TenantHeaderFilter}.
 * <p>
 * Counting is deliberately unsynchronised: a recorder belongs to exactly one thread, and the
 * ThreadLocal is what enforces that.
 */
public final class QuerySourceRecorder {

    /**
     * The tiers a read can be served from, in the order a request consults them.
     */
    public enum Source {
        /** Served from the Infinispan second-level cache without touching the database. */
        CACHE,
        /** Matched through the Hibernate Search / Lucene index rather than a SQL predicate. */
        INDEX,
        /** Fetched with SQL. For a cache-eligible entity this also means a cache miss. */
        DATABASE
    }

    private static final ThreadLocal<QuerySourceRecorder> CURRENT = new ThreadLocal<>();

    private int cacheHits;
    private int cacheMisses;
    private int indexQueries;
    private int databaseLoads;
    private String lastSql;
    private String tenantId;

    /**
     * Starts recording on this thread, replacing any previous recorder.
     *
     * @return the new recorder, so a caller that wants to read the tally later need not look it
     *         up again
     */
    public static QuerySourceRecorder begin() {
        QuerySourceRecorder recorder = new QuerySourceRecorder();
        CURRENT.set(recorder);
        return recorder;
    }

    /**
     * @return the recorder for this thread, or {@code null} when nothing is recording
     */
    public static QuerySourceRecorder current() {
        return CURRENT.get();
    }

    /**
     * Rebinds an existing recorder onto this thread. Used to carry one request's tally across the
     * servlet-to-worker-thread hand-off; see the class javadoc.
     */
    public static void bind(QuerySourceRecorder recorder) {
        if (recorder == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(recorder);
        }
    }

    /**
     * Mandatory in a {@code finally} on any thread that called {@link #begin()} or
     * {@link #bind(QuerySourceRecorder)} - see the class javadoc on pooled threads.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Records one observation against the thread's recorder, if any. A no-op when nothing is
     * recording, which is the normal case for work outside a request (startup, the seeder,
     * background indexing) - those paths must not be forced to set a recorder up just to be
     * instrumented.
     */
    public static void record(Source source) {
        QuerySourceRecorder recorder = CURRENT.get();
        if (recorder != null) {
            recorder.count(source);
        }
    }

    /**
     * Records the outcome of one second-level cache lookup. Misses are counted separately from
     * {@link Source#DATABASE} loads because the two are not the same measurement: a miss means the
     * entity was cache-eligible and absent, whereas a database load also covers entities that are
     * deliberately not cached at all. Reporting only the latter would make an entity excluded from
     * the cache on purpose look identical to one whose caching is broken.
     */
    /**
     * Records the SQL text of a read, so the search wrapper can tell whether the database did the
     * filtering or merely fetched rows whose ids Lucene had already chosen. Only the most recent
     * statement is kept: the question is always about the read just performed.
     */
    static void recordSql(String sql) {
        QuerySourceRecorder recorder = CURRENT.get();
        if (recorder != null) {
            recorder.lastSql = sql;
        }
    }

    /**
     * @return the SQL of the most recent read on this request, or {@code null} if none
     */
    String lastSql() {
        return lastSql;
    }

    /**
     * Captures the tenant while it is still in scope.
     * <p>
     * The summary is emitted from an async completion callback, on a container thread where
     * {@link com.empyrean.elide.tenant.TenantContext} has already been cleared - reading it there
     * yields {@code none} for every request, including correctly authenticated ones. Recording it
     * onto the tally, which already travels with the request, is what makes the tenant on the log
     * line true.
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * @return the tenant this request resolved to, or {@code null} if none was ever set
     */
    public String getTenantId() {
        return tenantId;
    }

    public static void recordCacheLookup(boolean hit) {
        if (hit) {
            record(Source.CACHE);
            return;
        }
        // A miss is an outcome, not a source - the request has not been served yet, and whatever
        // serves it next reports itself. So it is counted directly rather than through Source.
        QuerySourceRecorder recorder = CURRENT.get();
        if (recorder != null) {
            recorder.cacheMisses++;
        }
    }

    private void count(Source source) {
        switch (source) {
            case CACHE -> cacheHits++;
            case INDEX -> indexQueries++;
            case DATABASE -> databaseLoads++;
        }
    }

    public int getCacheHits() {
        return cacheHits;
    }

    public int getCacheMisses() {
        return cacheMisses;
    }

    public int getIndexQueries() {
        return indexQueries;
    }

    public int getDatabaseLoads() {
        return databaseLoads;
    }

    /**
     * @return whether anything at all was observed, so a request that touched no data (a 401, a
     *         static resource, an empty collection) can be logged differently from one that did
     */
    public boolean isEmpty() {
        return cacheHits == 0 && cacheMisses == 0 && indexQueries == 0 && databaseLoads == 0;
    }

    /**
     * @return a compact, fixed-shape summary suitable for one log line and for grepping
     */
    public String summary() {
        return "cacheHits=" + cacheHits
                + " cacheMisses=" + cacheMisses
                + " indexQueries=" + indexQueries
                + " dbLoads=" + databaseLoads;
    }

    @Override
    public String toString() {
        return summary();
    }
}
