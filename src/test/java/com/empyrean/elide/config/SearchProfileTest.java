package com.empyrean.elide.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what each remote-search profile's properties file declares, without starting a context in
 * that profile - the same style as {@code InfinispanProfileTest}, for the same reason: booting
 * either profile for real needs a reachable OpenSearch/Elasticsearch cluster, so a failure here
 * would say more about the test environment than about this configuration.
 */
class SearchProfileTest {

    @Test
    void opensearchProfileSwitchesToOpensearch() {
        assertThat(modeDeclaredIn("application-opensearch.properties"))
                .isEqualTo(SearchProperties.Mode.OPENSEARCH);
    }

    @Test
    void elasticsearchProfileSwitchesToElasticsearch() {
        assertThat(modeDeclaredIn("application-elasticsearch.properties"))
                .isEqualTo(SearchProperties.Mode.ELASTICSEARCH);
    }

    /**
     * Reads {@code svc.search.mode} out of one properties file, binding it the way Spring would
     * so an invalid enum value fails here rather than at startup.
     */
    private SearchProperties.Mode modeDeclaredIn(String resource) {
        try {
            List<PropertySource<?>> sources = new PropertiesPropertySourceLoader()
                    .load(resource, new ClassPathResource(resource));
            assertThat(sources).as("%s should exist and be loadable", resource).isNotEmpty();

            StandardEnvironment environment = new StandardEnvironment();
            sources.forEach(source -> environment.getPropertySources().addLast(source));

            return Binder.get(environment)
                    .bind("svc.search.mode", SearchProperties.Mode.class)
                    .orElseThrow(() -> new AssertionError("svc.search.mode not declared in " + resource));
        } catch (IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }
}
