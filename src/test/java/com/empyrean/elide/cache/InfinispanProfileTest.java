package com.empyrean.elide.cache;

import com.empyrean.elide.config.CacheProperties;
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
 * Pins what each profile's properties file declares, without starting a context in that profile.
 * <p>
 * Booting the infinispan profile for real is not a useful test here: it would need a reachable
 * Infinispan server with credentials and pre-created caches, so a failure would say more about the
 * test environment than about this configuration. What is worth pinning is the wiring itself -
 * that the shipped default is embedded and that activating the profile is what flips it, which is
 * exactly the pair a careless edit breaks.
 */
class InfinispanProfileTest {

    /**
     * {@code application.properties} deliberately does not declare {@code svc.cache.mode}, so
     * this pins {@link CacheProperties}' own field default instead of reading it out of a
     * properties file - see the class's own default and
     * {@code application.properties}' comment explaining why the line is absent.
     */
    @Test
    void defaultConfigurationShipsEmbedded() {
        assertThat(new CacheProperties().getMode())
                .as("the checked-in default must stay embedded - remote needs infrastructure")
                .isEqualTo(CacheProperties.Mode.EMBEDDED);
    }

    @Test
    void infinispanProfileSwitchesToRemote() {
        assertThat(modeDeclaredIn("application-infinispan.properties"))
                .isEqualTo(CacheProperties.Mode.REMOTE);
    }

    /**
     * Reads {@code svc.cache.mode} out of one properties file, binding it the way Spring would
     * so an invalid enum value fails here rather than at startup.
     */
    private CacheProperties.Mode modeDeclaredIn(String resource) {
        try {
            List<PropertySource<?>> sources = new PropertiesPropertySourceLoader()
                    .load(resource, new ClassPathResource(resource));
            assertThat(sources).as("%s should exist and be loadable", resource).isNotEmpty();

            StandardEnvironment environment = new StandardEnvironment();
            sources.forEach(source -> environment.getPropertySources().addLast(source));

            return Binder.get(environment)
                    .bind("svc.cache.mode", CacheProperties.Mode.class)
                    .orElseThrow(() -> new AssertionError("svc.cache.mode not declared in " + resource));
        } catch (IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }
}
