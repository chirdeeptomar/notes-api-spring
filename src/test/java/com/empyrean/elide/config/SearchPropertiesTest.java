package com.empyrean.elide.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the search backend switch: lucene by default and enabled.
 * <p>
 * {@code application.properties} deliberately declares neither {@code svc.search.enabled} nor
 * {@code svc.search.mode} - both already default to {@code true}/{@code LUCENE} on
 * {@link SearchProperties} itself, so this pins the field default directly by autowiring the
 * bean, the same way {@code CachePropertiesTest.defaultsToEmbeddedAndEnabled} does for
 * {@link CacheProperties}, rather than reading a mode out of a properties file.
 */
@SpringBootTest
class SearchPropertiesTest {

    @Autowired
    SearchProperties searchProperties;

    @Test
    void defaultsToLuceneAndEnabled() {
        assertThat(searchProperties.isEnabled()).isTrue();
        assertThat(searchProperties.getMode()).isEqualTo(SearchProperties.Mode.LUCENE);
    }
}
