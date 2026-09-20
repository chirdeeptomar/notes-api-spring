package com.empyrean.elide.config;

import com.empyrean.elide.datastore.TenantAwareDataSource;
import com.empyrean.elide.tenant.TenancyStrategy;
import com.empyrean.elide.tenant.TenantInfo;
import com.yahoo.elide.core.dictionary.Injector;
import com.yahoo.elide.core.utils.ClassScanner;
import com.yahoo.elide.datastores.aggregation.DefaultQueryValidator;
import com.yahoo.elide.datastores.aggregation.QueryEngine;
import com.yahoo.elide.datastores.aggregation.metadata.MetaDataStore;
import com.yahoo.elide.datastores.aggregation.query.DefaultQueryPlanMerger;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.ConnectionDetails;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.SQLQueryEngine;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.dialects.SQLDialectFactory;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.query.AggregateBeforeJoinOptimizer;
import com.yahoo.elide.modelconfig.DynamicConfiguration;
import com.yahoo.elide.spring.config.ElideConfigProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;

/**
 * Supplies the analytics {@link QueryEngine} so its SQL runs against the requesting tenant's
 * schema.
 * <p>
 * The aggregation store talks to the database over plain JDBC rather than through Hibernate, so it
 * cannot participate in {@link com.empyrean.elide.tenant.RequestTenantResolver} the way ordinary
 * JSON:API reads do. Scoping it means getting a {@link TenantAwareDataSource} into the engine's
 * {@link ConnectionDetails}.
 * <p>
 * <b>Why this bean exists rather than a {@code DataSource} bean.</b>
 * {@code ElideAutoConfiguration.AggregationStoreConfiguration.queryEngine(...)} builds its
 * {@code ConnectionDetails} from an injected, unqualified {@code DataSource} parameter, so a
 * tenant-aware {@code DataSource} bean would in principle be enough. In practice it is not: a
 * second {@code DataSource}-typed bean makes every other unqualified {@code DataSource} injection
 * point ambiguous - including autoconfigured ones this application cannot annotate, such as
 * {@code JpaBaseConfiguration}'s {@code entityManagerFactory}. Marking the bean
 * {@code autowireCandidate = false} resolves that ambiguity but also excludes it from
 * {@code queryEngine(...)}, so the wrapper was never consulted and analytics silently read one
 * fixed schema.
 * <p>
 * Defining the {@code QueryEngine} itself sidesteps the dilemma: the tenant-aware
 * {@code DataSource} is constructed here and handed straight to {@link ConnectionDetails}, never
 * exposed to the context as a bean, so no other injection point can see it. Declaring this bean
 * also suppresses the autoconfigured one, which is
 * {@code @ConditionalOnMissingBean(QueryEngine.class)}.
 * <p>
 * The construction below mirrors the autoconfiguration's own (verified by decompiling it with
 * {@code javap}) and must be kept in step with it across Elide upgrades: a {@link MetaDataStore}
 * over the dynamic HJSON configuration, the {@link AggregateBeforeJoinOptimizer}, a
 * {@link DefaultQueryPlanMerger} and a {@link DefaultQueryValidator}. Only the
 * {@code ConnectionDetails}' {@code DataSource} differs.
 */
@Configuration
public class AnalyticsQueryEngineConfiguration {

    @Bean
    public QueryEngine queryEngine(BeanFactory beanFactory, TenantInfo tenantInfo,
            TenancyStrategy tenancyStrategy, Optional<DynamicConfiguration> dynamicConfiguration,
            ElideConfigProperties settings, ClassScanner scanner, Injector injector) {

        boolean metadataEnabled = settings.getAggregationStore().getMetadataStore().isEnabled();

        ConnectionDetails connectionDetails = new ConnectionDetails(
                new TenantAwareDataSource(beanFactory, tenantInfo, tenancyStrategy),
                SQLDialectFactory.getDialect(settings.getAggregationStore().getDefaultDialect()));

        MetaDataStore metaDataStore = dynamicConfiguration
                .map(config -> new MetaDataStore(scanner, injector, config.getTables(),
                        config.getNamespaceConfigurations(), metadataEnabled))
                .orElseGet(() -> new MetaDataStore(scanner, injector, metadataEnabled));

        return new SQLQueryEngine(metaDataStore, unused -> connectionDetails,
                new HashSet<>(Arrays.asList(new AggregateBeforeJoinOptimizer(metaDataStore))),
                new DefaultQueryPlanMerger(metaDataStore),
                new DefaultQueryValidator(metaDataStore.getMetadataDictionary()));
    }
}
