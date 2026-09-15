package com.empyrean.elide.config;

import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.filter.dialect.jsonapi.DefaultFilterDialect;
import com.yahoo.elide.jsonapi.JsonApiSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds {@link DefaultFilterDialect} - the bracket-operator ({@code filter[type.field][op]=value})
 * JSON:API filter syntax that {@code infix}/{@code prefix} require - alongside the RSQL dialect
 * {@code elide-spring-boot-autoconfigure} registers by default.
 * <p>
 * {@code elide-spring-boot-autoconfigure} 7.2.0's {@code ElideAutoConfiguration.JsonApiConfiguration
 * .jsonApiSettingsBuilder(...)} (confirmed by decompiling the class) registers only an
 * {@code RSQLFilterDialect} as both the join and subquery filter dialect and never adds
 * {@code DefaultFilterDialect}. Per {@code JsonApiRequestScope}'s constructor (decompiled from
 * {@code elide-core}), {@code DefaultFilterDialect} is added automatically only as a *fallback*
 * when {@code JsonApiSettings.getJoinFilterDialects()}/{@code getSubqueryFilterDialects()} is
 * empty - since the autoconfigured RSQL dialect makes that list non-empty, the fallback never
 * runs and every bracket-operator query param (not just {@code infix}/{@code prefix} - any use of
 * that syntax) fails before reaching any data store, with
 * {@code "Invalid query parameter: filter[...]"}. This reproduces with plain
 * {@code filter[notes.email]=x} even with no search-store wiring involved (see
 * {@link ElideStoreConfiguration}), so it is a pre-existing gap in the autoconfiguration - Task 6
 * is what first exercises bracket-operator syntax, so it is fixed here.
 * <p>
 * The {@link JsonApiSettingsBuilderCustomizer} seam runs last inside
 * {@code jsonApiSettingsBuilder(...)} (via {@code ObjectProvider#orderedStream()}), after RSQL is
 * already registered, and {@code joinFilterDialect(single)}/{@code subqueryFilterDialect(single)}
 * append rather than replace - so this customizer adds {@link DefaultFilterDialect} alongside RSQL
 * rather than needing to remove or reorder it.
 * <p>
 * Split out of {@link ElideStoreConfiguration} (Task 6) rather than accreted into it: this bean
 * addresses JSON:API filter parsing, an unrelated concern from that class's two data-store
 * customizers, and keeping each {@code @Configuration} class to one concern keeps future changes
 * (e.g. Task 7's aggregation-store wiring) from having to reason about unrelated beans in the same
 * file.
 */
@Configuration
public class FilterDialectConfiguration {

    @Bean
    public JsonApiSettingsBuilderCustomizer defaultFilterDialectCustomizer(EntityDictionary dictionary) {
        return builder -> builder
                .joinFilterDialect(new DefaultFilterDialect(dictionary))
                .subqueryFilterDialect(new DefaultFilterDialect(dictionary));
    }
}
