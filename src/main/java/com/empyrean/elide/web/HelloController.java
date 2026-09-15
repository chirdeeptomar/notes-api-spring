package com.empyrean.elide.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A plain Spring MVC controller served under the same {@code /api/v1} base path as Elide's
 * generated endpoints, showing that hand-written REST resources coexist with Elide.
 * <p>
 * It needs no OpenAPI wiring: springdoc scans {@code @RestController} classes and Elide
 * contributes its own generated entity paths, so adding a resource requires no documentation code.
 */
@RestController
public class HelloController {

    @GetMapping(path = "/api/v1/hello", produces = MediaType.TEXT_PLAIN_VALUE)
    public String hello() {
        return "Hello From Notes API";
    }
}
