package com.empyrean.elide.cache;

import com.empyrean.elide.config.CacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the cache mode switch: embedded by default, remote opt-in, and each mode paired with the
 * right JCache provider class.
 * <p>
 * The provider pairing matters because both Infinispan JCache jars are on the classpath and each
 * registers a {@code javax.cache.spi.CachingProvider} through {@code ServiceLoader}. Selecting the
 * wrong class - or letting {@code Caching.getCachingProvider()} choose - would either fail at
 * startup or silently start an embedded cache while the configuration says remote.
 */
@SpringBootTest
class CachePropertiesTest {

    private static final String EMBEDDED_PROVIDER = "org.infinispan.jcache.embedded.JCachingProvider";
    private static final String REMOTE_PROVIDER = "org.infinispan.jcache.remote.JCachingProvider";

    @Autowired
    CacheProperties cacheProperties;

    @Test
    void defaultsToEmbeddedAndEnabled() {
        assertThat(cacheProperties.isEnabled()).isTrue();
        assertThat(cacheProperties.getMode()).isEqualTo(CacheProperties.Mode.EMBEDDED);
    }

    @Test
    void embeddedModeUsesTheEmbeddedProviderAndInfinispanXml() {
        CacheProperties props = new CacheProperties();
        props.setMode(CacheProperties.Mode.EMBEDDED);

        assertThat(props.cachingProvider()).isEqualTo(EMBEDDED_PROVIDER);
        assertThat(props.resolvedConfigUri()).isEqualTo("infinispan.xml");
    }

    @Test
    void remoteModeUsesTheRemoteProviderAndHotRodConfig() {
        CacheProperties props = new CacheProperties();
        props.setMode(CacheProperties.Mode.REMOTE);

        assertThat(props.cachingProvider()).isEqualTo(REMOTE_PROVIDER);
        assertThat(props.resolvedConfigUri()).isEqualTo("infinispan/hotrod-client.properties");
    }

    @Test
    void anExplicitConfigUriOverridesTheModeDefault() {
        CacheProperties props = new CacheProperties();
        props.setMode(CacheProperties.Mode.EMBEDDED);
        props.setConfigUri("custom-infinispan.xml");

        assertThat(props.resolvedConfigUri()).isEqualTo("custom-infinispan.xml");
    }

    /**
     * A blank value is treated as unset rather than as a config resource named "", which is what
     * an empty property in a properties file or an unset environment variable produces.
     */
    @Test
    void blankConfigUriFallsBackToTheModeDefault() {
        CacheProperties props = new CacheProperties();
        props.setMode(CacheProperties.Mode.REMOTE);
        props.setConfigUri("   ");

        assertThat(props.resolvedConfigUri()).isEqualTo("infinispan/hotrod-client.properties");
    }

    /**
     * The two modes must never resolve to the same provider - that would make the switch a no-op
     * and mask a misconfiguration as a working cache.
     */
    @Test
    void theTwoModesSelectDifferentProviders() {
        assertThat(CacheProperties.Mode.EMBEDDED.name())
                .isNotEqualTo(CacheProperties.Mode.REMOTE.name());

        CacheProperties embedded = new CacheProperties();
        embedded.setMode(CacheProperties.Mode.EMBEDDED);
        CacheProperties remote = new CacheProperties();
        remote.setMode(CacheProperties.Mode.REMOTE);

        assertThat(embedded.cachingProvider()).isNotEqualTo(remote.cachingProvider());
        assertThat(embedded.resolvedConfigUri()).isNotEqualTo(remote.resolvedConfigUri());
    }
}
