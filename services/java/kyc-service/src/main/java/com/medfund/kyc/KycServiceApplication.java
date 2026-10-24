package com.medfund.kyc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * kyc-service entry point (KYC horizontal, Plan A Phase 2).
 *
 * <p>Scans the rules-engine library subpackages explicitly so we pick up the
 * DRL compiler + facts + templates (VerificationFact / VERIFICATION category,
 * Plan A Phase 3) without dragging in rules-service's own HTTP layer
 * (controllers, exception handler, security config) — the same pattern
 * UserServiceApplication / ClaimsServiceApplication use.
 *
 * <p>Scanning {@code com.medfund.shared} brings in the party-neutral
 * {@code TenantEntitlementRegistry} + {@code EntitlementAspect}: the
 * {@code @RequiresEntitlement(KYC)} gate on {@code VerificationController} is
 * the in-service backstop (G4) behind the gateway's edge gate.
 */
@SpringBootApplication(scanBasePackages = {
        "com.medfund.kyc",
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
public class KycServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(KycServiceApplication.class, args);
    }
}
