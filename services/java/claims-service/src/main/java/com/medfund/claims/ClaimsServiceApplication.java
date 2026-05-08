package com.medfund.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Claims-service entry point.
 *
 * <p>Note the explicit subpackages of {@code com.medfund.rules}: rules-engine
 * is now both a library AND a Spring Boot service in its own right, so
 * scanning the whole {@code com.medfund.rules} root would pull in its
 * controllers, security config, exception handler, etc. — clashing with
 * claims-service's own equivalents. We pick only the library packages
 * (engine + facts + model + compiler + repository + template + the two
 * service classes claims-service actually consumes).
 */
@SpringBootApplication(scanBasePackages = {
        "com.medfund.claims",
        "com.medfund.shared",
        "com.medfund.rules.engine",
        "com.medfund.rules.compiler",
        "com.medfund.rules.consumer",
        "com.medfund.rules.fact",
        "com.medfund.rules.model",
        "com.medfund.rules.entity",
        "com.medfund.rules.repository",
        "com.medfund.rules.service",
        "com.medfund.rules.template",
})
public class ClaimsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimsServiceApplication.class, args);
    }
}
