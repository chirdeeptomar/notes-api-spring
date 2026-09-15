plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("io.freefair.lombok") version "8.10"
}

group = "com.empyrean"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

val elideVersion = "7.2.0"
val dataFakerVersion = "2.7.0"
// Spring Boot's BOM does not manage rest-assured (Quarkus's BOM does, which is why the
// source project can omit the version). Pinned explicitly to the newest stable release.
val restAssuredVersion = "5.5.2"

dependencies {
    implementation("com.yahoo.elide:elide-spring-boot-starter:$elideVersion")
    implementation("com.yahoo.elide:elide-datastore-search:$elideVersion")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // springdoc 3.x is the Spring Boot 4 line (springdoc-openapi 3.1.1's parent is
    // spring-boot-starter-parent 4.1.0); springdoc 2.x targets Spring Boot 3.x. A 2.x
    // version still RESOLVES under Spring Boot 4 but integrates against Spring Boot 3
    // APIs. Do not downgrade while this project is on Spring Boot 4.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    implementation("org.hibernate.search:hibernate-search-mapper-orm")
    implementation("org.hibernate.search:hibernate-search-backend-lucene")

    implementation("net.datafaker:datafaker:$dataFakerVersion")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.rest-assured:rest-assured:$restAssuredVersion")
    testImplementation("com.yahoo.elide:elide-test-helpers:$elideVersion")
}

// Spring Boot 4.1's dependency-management BOM imports the Groovy 5 platform (groovy-bom
// 5.0.6), which upgrades rest-assured 5.5.2's declared Groovy 4.0.22 dependency and breaks it
// at runtime: rest-assured's Groovy-based HTTPBuilder throws a NullPointerException out of
// ClosureMetaClass on the very first request under Groovy 5. A plain Gradle resolutionStrategy
// force() is not enough to win against an imported BOM's constraints, so override the managed
// version through the dependency-management plugin itself, back to the Groovy line rest-assured
// actually declares and was tested against.
dependencyManagement {
    dependencies {
        dependency("org.apache.groovy:groovy:4.0.22")
        dependency("org.apache.groovy:groovy-xml:4.0.22")
        dependency("org.apache.groovy:groovy-json:4.0.22")
    }
}

java {
    // A toolchain (not just source/target compatibility) is required because this machine's
    // default JDK (25) is newer than Gradle 8.14's bundled Kotlin compiler supports for parsing
    // build.gradle.kts — sourceCompatibility/targetCompatibility only govern bytecode level for
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
    // Elide's analytics MetaDataStore does a full ClassGraph classpath scan at startup to find
    // @Subselect-annotated classes, which needs more heap than Gradle's default test worker.
    maxHeapSize = "1536m"
}
