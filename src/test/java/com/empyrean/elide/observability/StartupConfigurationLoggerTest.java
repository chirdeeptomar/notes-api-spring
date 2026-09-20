package com.empyrean.elide.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.empyrean.elide.config.CacheProperties;
import com.empyrean.elide.config.SearchProperties;
import com.empyrean.elide.config.TenancyProperties;
import com.empyrean.elide.tenant.TenantInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the exact startup log lines {@link StartupConfigurationLogger} emits for tenancy, caching
 * and search - both what is logged when each subsystem is off, and the mode reported when it is
 * on. Constructed directly against real {@code *Properties}/{@link TenantInfo} instances rather
 * than a full {@code @SpringBootTest}, since this class does nothing but read those beans and
 * log - a unit test is both faster and a tighter pin on the log content itself than asserting
 * through a booted context would be.
 */
class StartupConfigurationLoggerTest {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        loggerUnderTest().addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        loggerUnderTest().detachAppender(appender);
    }

    private Logger loggerUnderTest() {
        return (Logger) LoggerFactory.getLogger(StartupConfigurationLogger.class);
    }

    @Test
    void logsEachSubsystemDisabledWhenAllAreOff() {
        run(tenancy(false), cache(false, null), search(false, null), tenantInfo());

        assertMessages(
                "Multi-tenancy: DISABLED",
                "Caching:       DISABLED",
                "Search:        DISABLED");
    }

    @Test
    void logsModeAndTenantCountWhenEverythingIsOn() {
        TenantInfo tenantInfo = tenantInfoWith("tenant_a,tenant_b", "key-a,key-b");

        run(tenancy(true), cache(true, CacheProperties.Mode.EMBEDDED),
                search(true, SearchProperties.Mode.LUCENE), tenantInfo);

        assertMessages(
                "Multi-tenancy: ENABLED, 2 tenant(s) configured",
                "Caching:       ENABLED, mode=EMBEDDED",
                "Search:        ENABLED, mode=LUCENE");
    }

    @Test
    void logsRemoteCacheModeAndElasticsearchSearchModeWhenSoConfigured() {
        run(tenancy(false), cache(true, CacheProperties.Mode.REMOTE),
                search(true, SearchProperties.Mode.ELASTICSEARCH), tenantInfo());

        assertMessages(
                "Multi-tenancy: DISABLED",
                "Caching:       ENABLED, mode=REMOTE",
                "Search:        ENABLED, mode=ELASTICSEARCH");
    }

    private void run(TenancyProperties tenancyProperties, CacheProperties cacheProperties,
            SearchProperties searchProperties, TenantInfo tenantInfo) {
        new StartupConfigurationLogger(tenancyProperties, cacheProperties, searchProperties, tenantInfo)
                .run(null);
    }

    private void assertMessages(String... expected) {
        List<String> actual = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(actual).containsExactly(expected);
    }

    private TenancyProperties tenancy(boolean enabled) {
        TenancyProperties props = new TenancyProperties();
        props.setEnabled(enabled);
        return props;
    }

    private CacheProperties cache(boolean enabled, CacheProperties.Mode mode) {
        CacheProperties props = new CacheProperties();
        props.setEnabled(enabled);
        if (mode != null) {
            props.setMode(mode);
        }
        return props;
    }

    private SearchProperties search(boolean enabled, SearchProperties.Mode mode) {
        SearchProperties props = new SearchProperties();
        props.setEnabled(enabled);
        if (mode != null) {
            props.setMode(mode);
        }
        return props;
    }

    private TenantInfo tenantInfo() {
        return tenantInfoWith("", "");
    }

    private TenantInfo tenantInfoWith(String ids, String keys) {
        TenantInfo tenantInfo = new TenantInfo();
        tenantInfo.setIds(ids.isBlank() ? List.of() : List.of(ids.split(",")));
        tenantInfo.setKeys(keys.isBlank() ? List.of() : List.of(keys.split(",")));
        invokeValidate(tenantInfo);
        return tenantInfo;
    }

    /**
     * {@link TenantInfo#validate()} is package-private {@code @PostConstruct}, called by Spring
     * in every other context - here it is invoked directly since this test builds
     * {@link TenantInfo} by hand rather than through a Spring container.
     */
    private void invokeValidate(TenantInfo tenantInfo) {
        try {
            var method = TenantInfo.class.getDeclaredMethod("validate");
            method.setAccessible(true);
            method.invoke(tenantInfo);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
