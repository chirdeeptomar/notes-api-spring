plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.lombok)
}

group = "com.empyrean"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

// Dependency versions live in gradle/libs.versions.toml, along with the reasoning for the
// non-obvious pins (springdoc's major line, rest-assured, Groovy).
dependencies {
    implementation(libs.elide.spring.boot.starter)
    implementation(libs.elide.datastore.search)

    // notes.search.mode=opensearch|elasticsearch. The Lucene backend (transitive via
    // elide-datastore-search above) stays on the classpath too, since mode=lucene is the default;
    // only hibernate.search.backend.type picks which one actually activates.
    implementation(libs.hibernate.search.backend.elasticsearch)

    // Declared explicitly although also reachable transitively: Note.java compiles against
    // jakarta.validation annotations directly, so it should not depend on a transitive path.
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)

    // Required, despite the app building and all tests passing without it: springdoc is what
    // documents the hand-written @RestControllers. Elide serves /api-docs itself, but that
    // document contains only its own generated entity paths - drop springdoc and /v3/api-docs
    // 404s and /api/v1/hello and /api/v1/uploads are documented nowhere. ApiDocsTest guards this.
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    implementation(libs.datafaker)

    // Hibernate second-level cache, backed by Infinispan through the JCache (JSR-107) bridge.
    // See libs.versions.toml for why the JCache route is used rather than Infinispan's native
    // Hibernate provider, and TenantAwareJCacheRegionFactory for how regions are tenant-scoped.
    // Both embedded and remote providers ship so notes.cache.mode selects between them at
    // startup; only the one named by the configured mode is ever instantiated.
    implementation(libs.hibernate.jcache)
    implementation(libs.infinispan.jcache)
    implementation(libs.infinispan.core)
    implementation(libs.infinispan.jcache.remote)
    implementation(libs.infinispan.client.hotrod)

    runtimeOnly(libs.h2)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.rest.assured)
    testImplementation(libs.elide.test.helpers)
}

// Spring Boot 4.1's dependency-management BOM imports the Groovy 5 platform (groovy-bom 5.0.6),
// which upgrades rest-assured's declared Groovy 4 dependency and breaks it at runtime:
// rest-assured's Groovy-based HTTPBuilder throws a NullPointerException out of ClosureMetaClass
// on the very first request under Groovy 5. A plain Gradle resolutionStrategy force() is not
// enough to win against an imported BOM's constraints, so override the managed version through
// the dependency-management plugin itself, back to the Groovy line rest-assured was tested
// against.
dependencyManagement {
    dependencies {
        dependency("org.apache.groovy:groovy:${libs.versions.groovy.get()}")
        dependency("org.apache.groovy:groovy-xml:${libs.versions.groovy.get()}")
        dependency("org.apache.groovy:groovy-json:${libs.versions.groovy.get()}")
    }
}

java {
    // A toolchain (not just source/target compatibility) is required because this machine's
    // default JDK (25) is newer than Gradle 8.14's bundled Kotlin compiler supports for parsing
    // build.gradle.kts - sourceCompatibility/targetCompatibility only govern bytecode level for
    // our own sources, not which JVM Gradle itself runs on. The toolchain makes Gradle select
    // Java 21 to run the build regardless of ambient JAVA_HOME.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    // Set directly rather than via a Spring profile: a test class annotated @ActiveProfiles would
    // replace spring.profiles.active and silently drop this, reintroducing the Lucene write.lock
    // contention between @SpringBootTest contexts. A system property survives regardless of profiles.
    systemProperty("spring.jpa.properties.hibernate.search.backend.directory.type", "local-heap")
    // Elide's analytics MetaDataStore does a full ClassGraph classpath scan at startup to find
    // @Subselect-annotated classes, which needs more heap than Gradle's default test worker.
    maxHeapSize = "1536m"
}
