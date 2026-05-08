package com.medfund.rules;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the rules service. Hosts the tenant rule CRUD
 * endpoints; the same module also exposes the {@code TenantRuleEngine} library
 * to claims-service for runtime evaluation, so the same JVM that mutates rules
 * is the one that owns the per-tenant {@link org.kie.api.runtime.KieContainer}
 * cache and can hot-reload it after every write.
 */
@SpringBootApplication(scanBasePackages = {"com.medfund.rules", "com.medfund.shared"})
public class RulesServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RulesServiceApplication.class, args);
    }
}
