package com.medfund.contributions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Contributions-service entry point.
 *
 * <p>Scans the rules-engine library subpackages explicitly (not the whole
 * {@code com.medfund.rules} root) so we don't pick up rules-service's own
 * HTTP-layer beans (controllers, exception handler, security config).
 * Mirrors the pattern in {@code ClaimsServiceApplication}.
 */
@SpringBootApplication(scanBasePackages = {
        "com.medfund.contributions",
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
public class ContributionsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContributionsServiceApplication.class, args);
    }
}
