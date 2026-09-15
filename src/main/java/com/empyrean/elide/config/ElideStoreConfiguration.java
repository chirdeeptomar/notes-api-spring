package com.empyrean.elide.config;

import com.empyrean.elide.datastore.TenantAwareDataSource;
import com.empyrean.elide.tenant.TenantInfo;
import com.yahoo.elide.core.datastore.DataStore;
import com.yahoo.elide.datastores.jpa.JpaDataStore;
import com.yahoo.elide.datastores.search.SearchDataStore;
import com.yahoo.elide.spring.config.ElideAutoConfiguration;
import com.yahoo.elide.spring.datastore.config.DataStoreBuilderCustomizer;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.util.ListIterator;

/**
 * Wraps Elide's JPA data store in a {@link SearchDataStore}, so read queries filtering or
 * sorting on a {@code @FullTextField} (i.e. {@code Note.body}) are served from the Lucene index
 * instead of the database; everything else - writes, all other reads - passes through
 * untouched.
 * <p>
 * Done by hand because {@code elide-spring-boot-autoconfigure} 7.2.0 autoconfigures the
 * aggregation (analytics) store but ships no search-store support.
 * <p>
 * <b>Actual API (differs from the sketch in the task brief):</b> {@code DataStoreBuilder} has
 * no {@code customizeStore} method. Its real surface (per {@code javap} against
 * {@code elide-spring-boot-autoconfigure-7.2.0.jar}) is:
 * {@code dataStores(List)}, {@code dataStores(Consumer<List<DataStore>>)}, {@code dataStore
 * (DataStore)}, {@code multiplexer(...)}, {@code build()}. There is no per-element "replace"
 * hook, so this customizer uses {@code dataStores(Consumer<List<DataStore>>)} to get direct
 * access to the backing, mutable list and replaces each {@link JpaDataStore} element in place
 * (via {@link ListIterator#set}) with a {@link SearchDataStore} wrapping it. Every other store
 * in the list (e.g. the aggregation store's {@code MetaDataStore}/{@code AggregationDataStore},
 * or Task 7's future additions) is left untouched, satisfying the "leave any other store
 * intact" requirement structurally rather than by ordering.
 * <p>
 * {@code elide-spring-boot-autoconfigure}'s {@code ElideAutoConfiguration.dataStoreBuilder(...)}
 * seeds the builder's list with the registered {@link JpaDataStore}(s) before any
 * {@link DataStoreBuilderCustomizer} bean runs, and consumes all such beans via
 * {@code ObjectProvider#orderedStream()} - the same pattern used for every other Elide
 * customizer seam (confirmed by decompiling {@code ElideAutoConfiguration}, whose other
 * "collect customizers and apply in order" bean methods all go through
 * {@code orderedStream().forEach(...)}). The built-in aggregation-store customizer runs at
 * {@link ElideAutoConfiguration#AGGREGATION_DATASTORE_CUSTOMIZER_ORDER} ({@code @Order(1)}) and
 * only *appends* stores via {@code DataStoreBuilder.dataStore(...)} - it never touches the
 * {@code JpaDataStore} entry, so this customizer's own order does not change correctness here.
 * It is still ordered deliberately, as {@code @Order(2)} - explicitly after the aggregation
 * customizer - so that if a later task's customizer also appends non-JPA stores before this one
 * runs, this customizer's "replace only JpaDataStore instances, leave everything else in the
 * list untouched" logic keeps working without having to reason about interleaving.
 * <p>
 * {@code indexOnStartup = true} runs a one-time MassIndexer pass, so rows that predate any
 * write in this process (e.g. those from {@code MockNoteSeeder}) are searchable immediately
 * rather than only after they are next updated.
 * <p>
 * <b>Task 7 addition - {@code defaultDataSource}:</b> {@code elide-spring-boot-autoconfigure}'s
 * {@code ElideAutoConfiguration.AggregationStoreConfiguration.queryEngine(...)} bean method
 * (confirmed by decompiling the class with {@code javap}) takes a plain {@code javax.sql.DataSource}
 * as its first parameter, with no {@code @Qualifier} - its {@code MethodParameters} attribute
 * names that parameter {@code defaultDataSource}. It builds its own internal
 * {@code ConnectionDetails} around whatever bean resolves there; there is no seam to supply a
 * {@code ConnectionDetails} bean directly; that possibility (sketched in the task brief) does not
 * exist in the real bytecode. Registering a {@code DataSource} bean named {@code defaultDataSource}
 * routes exactly that parameter to {@link TenantAwareDataSource} (Spring resolves a same-typed,
 * unqualified factory-method parameter by matching the parameter's own name against candidate bean
 * names first).
 * <p>
 * <b>Why {@code defaultDataSource} needs both {@code autowireCandidate = false} and a deferred,
 * by-name lookup instead of a {@code DataSource} constructor/method parameter:</b> a second
 * {@code DataSource}-typed bean in the context makes every <em>other</em> unqualified
 * {@code DataSource} injection point ambiguous by type, including ones this class cannot annotate
 * (e.g. {@code DataSourceInitializationAutoConfiguration.dataSourceScriptDatabaseInitializer} and
 * {@code JpaBaseConfiguration}'s {@code entityManagerFactory} bean, both autoconfigured with an
 * unqualified {@code DataSource dataSource} parameter). Two problems, found empirically, needed
 * two separate fixes:
 * <ol>
 * <li>If this bean method itself took a {@code DataSource} parameter (even named
 * {@code dataSource}, matching the raw bean by name), Spring could ask it to resolve that
 * parameter while <em>this very bean</em> was still under construction, throwing
 * {@code BeanCurrentlyInCreationException} - so it instead takes the always-unambiguous
 * {@link BeanFactory} and defers the by-name lookup of the raw {@code "dataSource"} bean to
 * {@link TenantAwareDataSource}'s first actual use (see its javadoc), well after context refresh
 * completes and no bean is "currently in creation".</li>
 * <li>That alone was not enough: with two ambiguous {@code DataSource} candidates in the context,
 * {@code entityManagerFactory} started resolving to this bean instead of the raw one - Hibernate's
 * own JPA bootstrap then failed to determine its SQL dialect, since the tenant-aware wrapper needs
 * a live {@code TenantContext} that does not exist yet during EMF creation. Marking the
 * autoconfigured {@code dataSource} bean definition {@code @Primary} via a
 * {@code BeanFactoryPostProcessor}/{@code BeanDefinitionRegistryPostProcessor} does not fix this:
 * {@code DataSourceAutoConfiguration}'s bean definition is registered late enough (behind a
 * deferred {@code @EnableAutoConfiguration} import, further affected by Spring Cloud Context's
 * {@code GenericScope} machinery this app pulls in) that consumers can already be resolving
 * {@code DataSource} before any first-party postprocessor observes the {@code dataSource}
 * definition at all - confirmed by instrumenting both postprocessor callbacks, neither of which ran
 * before the failure. {@code @Bean(autowireCandidate = false)} instead removes this bean from
 * candidacy for every unqualified, by-type injection point outright, while leaving it fully
 * resolvable by the explicit name match Elide's own {@code queryEngine(...)} uses (see above) -
 * this is the actual fix.</li>
 * </ol>
 * See {@code SchemaMultiTenantConnectionProvider} and {@code TenantSchemaInitializer} (both
 * constructor-injected, eagerly, with a plain {@code DataSource dataSource} parameter) for the
 * pre-existing, single-candidate-by-type injections that stay unaffected throughout.
 */
@Configuration
public class ElideStoreConfiguration {

    @Bean
    @Order(2)
    public DataStoreBuilderCustomizer searchStoreCustomizer(EntityManagerFactory entityManagerFactory) {
        return builder -> builder.dataStores(dataStores -> replaceJpaStoresWithSearchStores(dataStores, entityManagerFactory));
    }

    /**
     * Scopes the aggregation store's JDBC connections to the requesting tenant's schema. See the
     * class javadoc's "Task 7 addition" and "Why defaultDataSource needs both autowireCandidate =
     * false and a deferred, by-name lookup" sections for why this bean must be named
     * {@code defaultDataSource}, why it is not an autowire candidate, and why it resolves the raw
     * pooled {@code DataSource} lazily, by name, through {@link BeanFactory} rather than taking
     * one as a constructor/method parameter.
     */
    @Bean(name = "defaultDataSource", autowireCandidate = false)
    public DataSource tenantAwareDataSource(BeanFactory beanFactory, TenantInfo tenantInfo) {
        return new TenantAwareDataSource(beanFactory, tenantInfo);
    }

    private void replaceJpaStoresWithSearchStores(java.util.List<DataStore> dataStores,
            EntityManagerFactory entityManagerFactory) {
        ListIterator<DataStore> iterator = dataStores.listIterator();
        while (iterator.hasNext()) {
            DataStore store = iterator.next();
            if (store instanceof JpaDataStore jpaStore) {
                iterator.set(new SearchDataStore(jpaStore, entityManagerFactory, true));
            }
        }
    }
}
