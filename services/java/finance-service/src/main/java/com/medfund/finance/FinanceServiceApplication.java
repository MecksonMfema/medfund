package com.medfund.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Finance-service entry point.
 *
 * <p>Scans rules-engine library subpackages explicitly so we don't pick up
 * rules-service's own HTTP layer. Mirrors claims/contributions/user.
 */
@SpringBootApplication(scanBasePackages = {
        "com.medfund.finance",
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
public class FinanceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinanceServiceApplication.class, args);
    }
}
