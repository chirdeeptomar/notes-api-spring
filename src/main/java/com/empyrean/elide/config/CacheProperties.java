package com.empyrean.elide.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Selects how Infinispan backs Hibernate's second-level cache, under {@code notes.cache.*}.
 * <p>
 * The two modes differ in where the cache lives, not in how it is keyed - tenant scoping is
 * identical either way, because {@link TenantAwareJCacheRegionFactory} works against the JCache
 * {@code CacheManager} abstraction rather than against Infinispan directly.
 *
 * @see Mode
 */
@Component
@ConfigurationProperties(prefix = "notes.cache")
public class CacheProperties {

    /**
     * Where the Infinispan nodes backing the second-level cache run.
     */
    public enum Mode {

        /**
         * Infinispan runs inside this JVM. Entries live in the application's own heap, so reads
         * cost no network hop, but the cache competes with the application for memory and is cold
         * after every restart. Nodes still cluster over JGroups to exchange invalidations.
         * <p>
         * The default: it needs no infrastructure to run and no marshalling contract on cached
         * entities, since entries never leave the JVM that created them.
         */
        EMBEDDED("org.infinispan.jcache.embedded.JCachingProvider", "infinispan.xml"),

        /**
         * Infinispan runs as a separate server, reached over Hot Rod. Cache memory is isolated
         * from application heap and survives application restarts, and capacity scales with the
         * cache cluster rather than with application instances - at the cost of a network hop on
         * every cache read, a server to operate, and a marshalling contract on every cached
         * entity (entries are serialized to leave the JVM).
         */
        REMOTE("org.infinispan.jcache.remote.JCachingProvider", "hotrod-client.properties");

        private final String cachingProvider;
        private final String defaultConfigUri;

        Mode(String cachingProvider, String defaultConfigUri) {
            this.cachingProvider = cachingProvider;
            this.defaultConfigUri = defaultConfigUri;
        }

        /**
         * @return the JCache {@code CachingProvider} implementation for this mode. Named
         *         explicitly rather than discovered, because with both Infinispan JCache jars on
         *         the classpath each registers its own provider via {@code ServiceLoader} and
         *         {@code Caching.getCachingProvider()} would be ambiguous.
         */
        String cachingProvider() {
            return cachingProvider;
        }

        String defaultConfigUri() {
            return defaultConfigUri;
        }
    }

    /**
     * Whether the second-level cache is switched on at all. When false, no region factory is
     * registered and every read goes to the database.
     */
    private boolean enabled = true;

    /** Where Infinispan runs. Defaults to {@link Mode#EMBEDDED}. */
    private Mode mode = Mode.EMBEDDED;

    /**
     * Cache configuration resource, overriding the mode's default
     * ({@code infinispan.xml} for embedded, {@code hotrod-client.properties} for remote).
     */
    private String configUri;

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

    public String getConfigUri() {
        return configUri;
    }

    public void setConfigUri(String configUri) {
        this.configUri = configUri;
    }

    /** @return the JCache provider class for the selected mode */
    public String cachingProvider() {
        return mode.cachingProvider();
    }

    /** @return the configured resource, or the selected mode's default when unset */
    public String resolvedConfigUri() {
        return configUri != null && !configUri.isBlank() ? configUri : mode.defaultConfigUri();
    }
}
