package com.empyrean.elide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Spring Boot port of the Notes API.
 * <p>
 * Elide's JSON:API and GraphQL controllers, its OpenAPI document, and the analytics
 * (aggregation) data store are all contributed by {@code elide-spring-boot-starter}'s
 * autoconfiguration, driven by the {@code elide.*} properties in
 * {@code application.properties}.
 */
@SpringBootApplication
public class NotesApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotesApiApplication.class, args);
    }
}
