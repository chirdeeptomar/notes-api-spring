package com.empyrean.elide.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Selects which Hibernate Search backend serves full-text search on {@code Note.body}, under
 * {@code svc.search.mode}.
 * <p>
 * Binding this rather than reading {@code hibernate.search.backend.type} directly gives a
 * validated startup failure on an unrecognized mode instead of a silently-ignored property, and
 * gives the rest of the app (logging, diagnostics) a single source of truth for which mode is
 * active. The actual backend wiring for {@link Mode#OPENSEARCH} and {@link Mode#ELASTICSEARCH}
 * lives entirely in {@code application-opensearch.properties} /
 * {@code application-elasticsearch.properties} - this class does not configure Hibernate Search
 * itself.
 *
 * @see Mode
 */
@Component
@ConfigurationProperties(prefix = "svc.search")
public class SearchProperties {

    /**
     * Which Hibernate Search backend indexes {@code Note.body}.
     * <p>
     * Solr is not an option: Hibernate Search 8.x (what {@code elide-datastore-search} pulls in)
     * ships only Lucene and Elasticsearch backends. Hibernate Search's Solr backend existed in
     * its 5.x line and was removed years before 8.x; supporting Solr here would mean replacing
     * {@code elide-datastore-search}'s indexing entirely with a hand-written {@code DataStore}
     * talking to Solr's own client, not a config change.
     */
    public enum Mode {

        /**
         * Hibernate Search indexes to the local filesystem via its Lucene backend. The default:
         * it needs no infrastructure to run.
         */
        LUCENE,

        /**
         * Hibernate Search indexes to a remote OpenSearch cluster via its Elasticsearch backend
         * (the same artifact serves both distributions; see
         * {@code application-opensearch.properties} for the {@code hibernate.search.backend.version}
         * {@code opensearch:} prefix that tells them apart).
         */
        OPENSEARCH,

        /**
         * Hibernate Search indexes to a remote Elasticsearch cluster via its Elasticsearch
         * backend.
         */
        ELASTICSEARCH
    }

    /**
     * Whether full-text search is switched on at all. Off by default - a deployment that never
     * sets {@code svc.search.enabled} gets no search index, needing no Lucene/Elasticsearch
     * infrastructure at all. When false, {@code Note} is served by the plain JPA store instead of
     * {@code SearchDataStore}. {@code infix}/{@code prefix} filters on {@code body} keep working
     * either way - {@code infix}/{@code prefix} are operators Elide's own predicate engine
     * understands generically, not something exclusive to {@code SearchDataStore} - but every
     * such query now falls through to a SQL {@code LIKE} predicate against the database instead
     * of being served from the index. See
     * {@link com.empyrean.elide.observability.QuerySourceAwareSearchDataStore} for how that
     * database-served path is distinguished from an index-served one in the query-source log.
     * This application's own default profile opts back in explicitly (see
     * {@code application.properties}), since the sample demonstrates the search path.
     */
    private boolean enabled = false;

    /** Which backend indexes {@code Note.body}. Defaults to {@link Mode#LUCENE}. */
    private Mode mode = Mode.LUCENE;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }
}
