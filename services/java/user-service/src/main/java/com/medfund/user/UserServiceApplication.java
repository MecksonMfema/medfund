package com.medfund.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * User-service entry point.
 *
 * <p>Scans rules-engine library subpackages explicitly so we don't pick up
 * rules-service's own HTTP layer (controllers, exception handler, security
 * config) — see ClaimsServiceApplication / ContributionsServiceApplication
 * for the same pattern.
 */
@SpringBootApplication(scanBasePackages = {
        "com.medfund.user",
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
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
