package com.empyrean.elide.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the search backend switch: lucene mode, and this app's own default profile opting back
 * into search explicitly.
 * <p>
 * {@link SearchProperties#enabled} itself defaults to {@code false} - a deployment that never
 * sets {@code svc.search.enabled} gets no search index at all (see
 * {@code frameworkDefaultIsDisabled} below, which pins that directly against an unbound
 * instance). {@code application.properties} opts back in explicitly with
 * {@code svc.search.enabled=true}, since this sample demonstrates the search path - so a
 * {@code @SpringBootTest}-loaded instance is enabled, which {@code appProfileEnablesSearch}
 * pins.
 */
@SpringBootTest
class SearchPropertiesTest {

    @Autowired
    SearchProperties searchProperties;

    @Test
    void appProfileEnablesSearch() {
        assertThat(searchProperties.isEnabled()).isTrue();
        assertThat(searchProperties.getMode()).isEqualTo(SearchProperties.Mode.LUCENE);
    }

    @Test
    void frameworkDefaultIsDisabled() {
        assertThat(new SearchProperties().isEnabled()).isFalse();
    }
}
