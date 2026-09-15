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
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    implementation("org.hibernate.search:hibernate-search-mapper-orm")
    implementation("org.hibernate.search:hibernate-search-backend-lucene")

    implementation("net.datafaker:datafaker:$dataFakerVersion")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.rest-assured:rest-assured:$restAssuredVersion")
    testImplementation("com.yahoo.elide:elide-test-helpers:$elideVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
