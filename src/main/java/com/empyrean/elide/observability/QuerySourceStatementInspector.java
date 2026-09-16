package com.empyrean.elide.observability;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts every SQL statement Hibernate issues, so a request that fell through to the database is
 * distinguishable from one served entirely from cache or index.
 * <p>
 * <b>Why a StatementInspector rather than {@code spring.jpa.show-sql}.</b> {@code show-sql} prints
 * the SQL and nothing else: no tenant, no request, and no correlation with the cache lookups that
 * preceded it. Reading it tells you that some SQL ran somewhere, which is precisely the question
 * that is easy to answer and rarely the one being asked. A {@link StatementInspector} sits on the
 * same path but runs inside the session, so what it observes can be attributed to the request that
 * caused it via {@link QuerySourceRecorder}.
 * <p>
 * <b>What counts as a database load.</b> Only reads. Writes are excluded deliberately: an INSERT
 * or UPDATE is not evidence that the cache failed to serve something, and counting them would make
 * every write look like a cache miss and hide the reads that actually matter. The test is a prefix
 * check on the statement, which is sufficient because Hibernate always emits the verb first.
 * <p>
 * This returns the SQL unmodified - it is an observer, not a rewriter.
 */
public class QuerySourceStatementInspector implements StatementInspector {

    @Override
    public String inspect(String sql) {
        if (sql != null && isRead(sql)) {
            QuerySourceRecorder.record(QuerySourceRecorder.Source.DATABASE);
            QuerySourceRecorder.recordSql(sql);
        }
        return sql;
    }

    /**
     * Hibernate emits the verb first, so a prefix test identifies reads without parsing. Leading
     * whitespace is possible on formatted SQL, hence the strip.
     */
    private static boolean isRead(String sql) {
        String trimmed = sql.stripLeading();
        return trimmed.regionMatches(true, 0, "select", 0, "select".length());
    }
}
