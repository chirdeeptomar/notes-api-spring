package com.empyrean.elide.observability;

import com.yahoo.elide.core.datastore.DataStore;
import com.yahoo.elide.core.datastore.DataStoreIterable;
import com.yahoo.elide.core.datastore.DataStoreTransaction;
import com.yahoo.elide.core.datastore.wrapped.TransactionWrapper;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.request.EntityProjection;
import com.yahoo.elide.core.RequestScope;
import com.yahoo.elide.datastores.search.SearchDataStore;

/**
 * Wraps the {@link SearchDataStore} so it is visible in the logs when a read was answered by the
 * Lucene index rather than by SQL.
 * <p>
 * <b>Why a wrapper and not a log statement inside the search store.</b> The index-or-database
 * decision lives in {@code SearchDataTransaction#loadObjects}, which is Elide's code and cannot be
 * edited, and it is made by a private {@code canSearch(...)} that is not reachable from outside.
 * Reimplementing that predicate here to predict what Elide will do would be the worst option
 * available: it would be a second copy of a non-trivial rule (filter support, sortability, ngram
 * bounds) that silently drifts from the real one on any Elide upgrade, and would then report the
 * wrong tier with total confidence.
 * <p>
 * What is observable without duplicating the rule is the <em>consequence</em>, in the SQL each
 * path emits. When the database filters, the predicate appears in the statement
 * ({@code where n1_0.body like ?}); when Lucene filters, it hands Hibernate a set of ids and the
 * rows are fetched by primary key ({@code where n1_0.id in (?)}), with the filtered column absent.
 * So a filtered read whose SQL never mentioned the filtered field was served by the index. That is
 * inferred from observed behaviour rather than from a copied predicate, and it degrades honestly:
 * if a future Elide version changes how the index loads entities, the SQL changes with it and the
 * accompanying test fails rather than the label silently going stale.
 * <p>
 * Reads without a filter are never index-eligible and are not examined at all, so the common path
 * carries no measurement overhead.
 */
public class QuerySourceAwareSearchDataStore implements DataStore {

    private final SearchDataStore delegate;

    public QuerySourceAwareSearchDataStore(SearchDataStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void populateEntityDictionary(EntityDictionary dictionary) {
        delegate.populateEntityDictionary(dictionary);
    }

    @Override
    public DataStoreTransaction beginTransaction() {
        return delegate.beginTransaction();
    }

    @Override
    public DataStoreTransaction beginReadTransaction() {
        return new RecordingTransaction(delegate.beginReadTransaction());
    }

    /**
     * Delegates every operation untouched, observing only which tier answered a filtered read.
     * Extends Elide's own {@code TransactionWrapper} - the same base {@code SearchDataTransaction}
     * uses - so that operations this class does not care about keep working unchanged as the
     * {@link DataStoreTransaction} interface evolves.
     */
    private static final class RecordingTransaction extends TransactionWrapper {

        private RecordingTransaction(DataStoreTransaction tx) {
            super(tx);
        }

        @Override
        public <T> DataStoreIterable<T> loadObjects(EntityProjection projection, RequestScope scope) {
            if (projection.getFilterExpression() == null) {
                // Not index-eligible: Elide's search transaction returns early for unfiltered
                // reads. Whatever serves it reports itself.
                return super.loadObjects(projection, scope);
            }

            DataStoreIterable<T> result = super.loadObjects(projection, scope);

            QuerySourceRecorder recorder = QuerySourceRecorder.current();
            if (recorder != null && wasAnsweredByIndex(recorder, projection)) {
                QuerySourceRecorder.record(QuerySourceRecorder.Source.INDEX);
            }
            return result;
        }

        /**
         * A filtered read was answered by Lucene if the SQL it issued never mentioned the field
         * being filtered on.
         * <p>
         * This is what the two paths actually look like, confirmed by capturing the SQL of each.
         * When the database does the filtering, the predicate is in the statement:
         * {@code ... from note n1_0 where n1_0.body like ?}. When Lucene does it, Hibernate Search
         * returns the matching ids and Hibernate fetches those rows by primary key:
         * {@code ... from note n1_0 where n1_0.id in (?)} - the filtered column is absent.
         * <p>
         * An earlier version of this check tested for "issued no SQL at all", on the assumption
         * that an index hit avoids the database. It does not: {@code SearchResult#hits()} loads
         * the entities through Hibernate, so an index-served read always issues a SELECT, and that
         * check reported every index query as a plain database read. The field test is the
         * distinction that actually holds.
         */
        private boolean wasAnsweredByIndex(QuerySourceRecorder recorder, EntityProjection projection) {
            String sql = recorder.lastSql();
            if (sql == null) {
                return false;
            }
            // Only the WHERE clause decides this. The filtered column almost always appears in the
            // SELECT list too - an index-served read still fetches the whole row - so testing the
            // statement as a whole reports every index query as a database filter.
            String whereClause = whereClauseOf(sql);
            if (whereClause == null) {
                // No predicate at all: the database cannot have done the filtering.
                return true;
            }
            for (String field : filteredFields(projection)) {
                // Hibernate emits snake_case column names for camelCase fields.
                if (whereClause.contains(toColumnName(field))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * @return everything after the last {@code where} in the statement, lowercased, or
         *         {@code null} when there is no predicate. The last occurrence rather than the
         *         first so that a subquery's {@code where} does not truncate the outer one.
         */
        private static String whereClauseOf(String sql) {
            String lower = sql.toLowerCase();
            int where = lower.lastIndexOf(" where ");
            return where < 0 ? null : lower.substring(where);
        }

        private static java.util.Set<String> filteredFields(EntityProjection projection) {
            java.util.Set<String> fields = new java.util.LinkedHashSet<>();
            projection.getFilterExpression()
                    .accept(new com.yahoo.elide.core.filter.expression.PredicateExtractionVisitor())
                    .forEach(predicate -> fields.add(predicate.getField()));
            return fields;
        }

        /** {@code createdDate} -> {@code created_date}, matching Hibernate's default naming. */
        private static String toColumnName(String field) {
            return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        }
    }
}
