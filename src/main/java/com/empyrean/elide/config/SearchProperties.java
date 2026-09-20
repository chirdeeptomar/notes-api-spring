package com.empyrean.elide.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Selects which Hibernate Search backend serves full-text search on {@code Note.body}, under
 * {@code svc.search.mode}, and holds the remote backends' connection details.
 * <p>
 * Binding this rather than reading {@code hibernate.search.backend.*} directly gives a validated
 * startup failure on an unrecognized mode instead of a silently-ignored property, and gives the
 * rest of the app (logging, diagnostics) a single source of truth for which mode is active. The
 * actual {@code hibernate.search.backend.*} properties for every mode are set programmatically
 * from this class by {@code HibernateTenancyConfiguration.searchBackendCustomizer} - not
 * statically in {@code application.properties} - the same reason {@link CacheProperties}' mode
 * drives {@code ConfigSettings.PROVIDER}/{@code CONFIG_URI} there instead of a static property:
 * a single {@code svc.search.mode} switch has to change several dependent properties together
 * (backend type, host, credentials, version) without the two ever disagreeing.
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
         * (the same artifact serves both distributions - Hibernate Search tells them apart
         * purely via {@code backend.version}'s {@code opensearch:} prefix, since OpenSearch's
         * version numbers diverged from Elasticsearch's after the 2021 fork).
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

    /**
     * Local filesystem directory for the Lucene backend's indexes. Ignored by
     * {@link Mode#OPENSEARCH}/{@link Mode#ELASTICSEARCH}.
     */
    private String indexPath = "build/lucene-indexes";

    /**
     * {@code host:port} pairs for the remote cluster ({@link Mode#OPENSEARCH}/
     * {@link Mode#ELASTICSEARCH} only). Comma-separated for more than one node.
     */
    private String hosts = "localhost:9200";

    /** {@code http} or {@code https}, for the remote cluster connection. */
    private String protocol = "http";

    /** Basic auth username for the remote cluster, or blank for none. */
    private String username = "";

    /** Basic auth password for the remote cluster, or blank for none. */
    private String password = "";

    /**
     * The remote cluster's version, as Hibernate Search's Elasticsearch backend expects it: a
     * bare major version (e.g. {@code "9"}) for {@link Mode#ELASTICSEARCH}, or the same string
     * prefixed with {@code opensearch:} for {@link Mode#OPENSEARCH} (e.g.
     * {@code "opensearch:2"}) - {@code HibernateTenancyConfiguration.searchBackendCustomizer}
     * adds that prefix automatically for {@link Mode#OPENSEARCH}, so this field itself never
     * carries it. A trailing {@code .x} (e.g. {@code "9.x"}) looks natural but is rejected -
     * Hibernate Search parses this into an {@code ElasticsearchVersion} and only accepts
     * {@code x.y.z-qualifier}, {@code <distribution>:x.y.z-qualifier}, or a bare distribution
     * name; an incomplete numeric version (just {@code "9"}, no minor/patch) is fine, a literal
     * {@code x} placeholder is not.
     */
    private String version = "9";

    /**
     * Escape hatch for a managed cluster that reports a version string outside Hibernate
     * Search's officially tested range: disables its startup validation of the live cluster's
     * reported version against {@link #version}.
     */
    private boolean versionCheckEnabled = true;

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

    public String getIndexPath() {
        return indexPath;
    }

    public void setIndexPath(String indexPath) {
        this.indexPath = indexPath;
    }

    public String getHosts() {
        return hosts;
    }

    public void setHosts(String hosts) {
        this.hosts = hosts;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public boolean isVersionCheckEnabled() {
        return versionCheckEnabled;
    }

    public void setVersionCheckEnabled(boolean versionCheckEnabled) {
        this.versionCheckEnabled = versionCheckEnabled;
    }

    /**
     * @return the {@code hibernate.search.backend.version} value for the current mode - {@code
     *         version}, prefixed with {@code opensearch:} for {@link Mode#OPENSEARCH}. Not
     *         meaningful for {@link Mode#LUCENE}, which has no such property.
     */
    public String resolvedBackendVersion() {
        return mode == Mode.OPENSEARCH ? "opensearch:" + version : version;
    }
}
