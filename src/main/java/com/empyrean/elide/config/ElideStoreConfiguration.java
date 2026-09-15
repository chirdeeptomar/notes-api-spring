package com.empyrean.elide.config;

import com.yahoo.elide.core.datastore.DataStore;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.filter.dialect.jsonapi.DefaultFilterDialect;
import com.yahoo.elide.datastores.jpa.JpaDataStore;
import com.yahoo.elide.datastores.search.SearchDataStore;
import com.yahoo.elide.jsonapi.JsonApiSettingsBuilderCustomizer;
import com.yahoo.elide.spring.config.ElideAutoConfiguration;
import com.yahoo.elide.spring.datastore.config.DataStoreBuilderCustomizer;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

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
 * <b>A second, independent gap this class also closes:</b> {@code infix}/{@code prefix}
 * filters use JSON:API's bracket-operator query syntax
 * ({@code filter[notes.body][infix]=term}), which only {@link DefaultFilterDialect} parses.
 * {@code elide-spring-boot-autoconfigure} 7.2.0's {@code ElideAutoConfiguration.JsonApiConfiguration
 * .jsonApiSettingsBuilder(...)} (confirmed by decompiling the class) registers only an
 * {@code RSQLFilterDialect} as both the join and subquery filter dialect and never adds
 * {@code DefaultFilterDialect}. Per {@code JsonApiRequestScope}'s constructor (decompiled from
 * {@code elide-core}), {@code DefaultFilterDialect} is added automatically only as a *fallback*
 * when {@code JsonApiSettings.getJoinFilterDialects()}/{@code getSubqueryFilterDialects()} is
 * empty - since the autoconfigured RSQL dialect makes that list non-empty, the fallback never
 * runs and every bracket-operator query param (not just {@code infix}/{@code prefix} - any use
 * of that syntax) fails before reaching any data store, with
 * {@code "Invalid query parameter: filter[...]"}. This reproduces with plain
 * {@code filter[notes.email]=x} even with no {@link SearchDataStore} involved, so it is a
 * pre-existing gap in the autoconfiguration, not something introduced by the search wiring
 * above - but Task 6 is what first exercises bracket-operator syntax, so it is fixed here.
 * The {@link JsonApiSettingsBuilderCustomizer} seam runs last inside
 * {@code jsonApiSettingsBuilder(...)} (again via {@code ObjectProvider#orderedStream()}), after
 * RSQL is already registered, and {@code joinFilterDialect(single)}/
 * {@code subqueryFilterDialect(single)} append rather than replace - so this customizer adds
 * {@link DefaultFilterDialect} alongside RSQL rather than needing to remove or reorder it.
 */
@Configuration
public class ElideStoreConfiguration {

    @Bean
    @Order(2)
    public DataStoreBuilderCustomizer searchStoreCustomizer(EntityManagerFactory entityManagerFactory) {
        return builder -> builder.dataStores(dataStores -> replaceJpaStoresWithSearchStores(dataStores, entityManagerFactory));
    }

    /**
     * Adds {@link DefaultFilterDialect} - the bracket-operator ({@code filter[type.field][op]=value})
     * JSON:API filter syntax that {@code infix}/{@code prefix} require - alongside the
     * RSQL dialect {@code elide-spring-boot-autoconfigure} registers by default. See the class
     * Javadoc for why this is otherwise silently missing.
     */
    @Bean
    public JsonApiSettingsBuilderCustomizer defaultFilterDialectCustomizer(EntityDictionary dictionary) {
        return builder -> builder
                .joinFilterDialect(new DefaultFilterDialect(dictionary))
                .subqueryFilterDialect(new DefaultFilterDialect(dictionary));
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
