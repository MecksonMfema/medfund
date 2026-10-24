package com.medfund.kyc.integration;

import com.medfund.kyc.KycServiceApplication;
import com.medfund.kyc.controller.VerificationController;
import com.medfund.kyc.dto.VerificationResponse;
import com.medfund.kyc.entity.KycDocument;
import com.medfund.kyc.entity.KycVerification;
import com.medfund.kyc.repository.KycDocumentRepository;
import com.medfund.kyc.repository.KycVerificationRepository;
import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * End-to-end slice for the kyc-service skeleton (Plan A Phase 2). Proves the
 * boundary the whole phase exists to establish:
 *
 * <ul>
 *   <li>the party-neutral {@code kyc_verifications} / {@code kyc_documents}
 *       schema applies and {@code GET /api/v1/verifications} returns the tenant's
 *       rows (mapped party-neutrally) for an <em>entitled</em> tenant;</li>
 *   <li>the {@code @RequiresEntitlement(KYC)} gate on {@code VerificationController}
 *       denies an <em>un-entitled</em> tenant with 403 (G4 — the in-service
 *       backstop behind the gateway edge gate).</li>
 * </ul>
 *
 * <p>Runs at {@code WebEnvironment.NONE} and drives the controller bean directly
 * (an AOP proxy, so the entitlement aspect fires) within a Reactor tenant
 * context — the same shape as {@code TenantModuleEntitlementIT}. The HTTP/gateway
 * round-trip is the phase's manual verification step.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = KycServiceApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/kyc-skeleton-migration",
        "spring.flyway.baseline-on-migrate=true",
})
@Import(KycSkeletonIT.SecurityStub.class)
class KycSkeletonIT extends AbstractPostgresIntegrationTest {

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";
    private static final String UNENTITLED_TENANT = "00000000-0000-4000-8000-000000000099";

    @Autowired private VerificationController verificationController;
    @Autowired private KycVerificationRepository verificationRepository;
    @Autowired private KycDocumentRepository documentRepository;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void entitleTenantAndResetTables() {
        // The gate reads public.tenant_module_entitlements on every call — seed
        // the entitled tenant's KYC row before the first list() so the aspect
        // (and its 30s cache) sees it enabled from the outset. Truncate the KYC
        // tables on the session user so each test starts from a known state.
        db.sql("TRUNCATE kyc_verifications, kyc_documents").then()
                .block(Duration.ofSeconds(10));
        db.sql("""
                INSERT INTO public.tenant_module_entitlements (tenant_id, module_key, enabled)
                VALUES (CAST(:t AS uuid), 'KYC', TRUE)
                ON CONFLICT (tenant_id, module_key) DO UPDATE SET enabled = TRUE
                """)
                .bind("t", TENANT_ID)
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void entitledTenant_listVerifications_returnsEmpty_whenNoRows() {
        StepVerifier.create(
                        verificationController.list()
                                .contextWrite(TenantTestContext.putExplicit(TENANT_ID)))
                .expectComplete()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void entitledTenant_listVerifications_mapsSeededRowPartyNeutrally() {
        UUID subjectId = UUID.randomUUID();
        seedVerification(subjectId, "MEMBER", "LIFE", "pending_verification", (short) 1, "clear");

        StepVerifier.create(
                        verificationController.list()
                                .contextWrite(TenantTestContext.putExplicit(TENANT_ID)))
                .assertNext(resp -> {
                    assertEquals("MEMBER", resp.subjectType(), "subjectType");
                    assertEquals(subjectId, resp.subjectId(), "subjectId");
                    assertEquals("LIFE", resp.insuranceLine(), "insuranceLine (contextual, G6)");
                    assertEquals("pending_verification", resp.verificationStatus(), "status");
                    assertEquals((short) 1, resp.verificationLevel().shortValue(), "level");
                    assertEquals("clear", resp.screeningResult(), "screeningResult");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void repositories_roundTripBySubject() {
        UUID subjectId = UUID.randomUUID();
        seedVerification(subjectId, "PROVIDER", "HEALTH", "verified", (short) 2, "clear");
        seedDocument(subjectId, "PROVIDER", "NATIONAL_ID");

        StepVerifier.create(
                        verificationRepository.findBySubject("PROVIDER", subjectId)
                                .contextWrite(TenantTestContext.putExplicit(TENANT_ID)))
                .assertNext(v -> {
                    assertEquals("verified", v.getVerificationStatus(), "status");
                    assertEquals((short) 2, v.getVerificationLevel().shortValue(), "level");
                    assertEquals("HEALTH", v.getInsuranceLine(), "line");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(15));

        StepVerifier.create(
                        documentRepository.findBySubject("PROVIDER", subjectId)
                                .contextWrite(TenantTestContext.putExplicit(TENANT_ID)))
                .assertNext(d -> {
                    assertEquals("NATIONAL_ID", d.getDocumentType(), "documentType");
                    assertEquals("uploaded", d.getStatus(), "status");
                    assertEquals(subjectId, d.getSubjectId(), "subjectId");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(15));
    }

    @Test
    void unentitledTenant_listVerifications_isForbidden() {
        StepVerifier.create(
                        verificationController.list()
                                .contextWrite(TenantTestContext.putExplicit(UNENTITLED_TENANT)))
                .expectErrorSatisfies(error -> {
                    if (!(error instanceof ResponseStatusException rse)
                            || rse.getStatusCode() != HttpStatus.FORBIDDEN) {
                        throw new AssertionError(
                                "expected 403 ResponseStatusException from the KYC gate, got: " + error);
                    }
                })
                .verify(Duration.ofSeconds(15));
    }

    private void seedVerification(UUID subjectId, String subjectType, String line,
                                  String status, short level, String screening) {
        db.sql("""
                INSERT INTO kyc_verifications
                    (subject_type, subject_id, insurance_line, verification_status,
                     verification_level, method, screening_result)
                VALUES (:st, :sid, :line, :status, :level, 'document', :screening)
                """)
                .bind("st", subjectType)
                .bind("sid", subjectId)
                .bind("line", line)
                .bind("status", status)
                .bind("level", level)
                .bind("screening", screening)
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(10));
    }

    private void seedDocument(UUID subjectId, String subjectType, String documentType) {
        db.sql("""
                INSERT INTO kyc_documents
                    (subject_type, subject_id, document_type, file_service_ref, bucket,
                     content_type, size_bytes, sha256, uploaded_by, uploaded_by_email)
                VALUES (:st, :sid, :dt, 'file-ref-1', 'kyc-bucket',
                        'image/png', 1024, 'deadbeef', :uid, 'uploader@it.example')
                """)
                .bind("st", subjectType)
                .bind("sid", subjectId)
                .bind("dt", documentType)
                .bind("uid", UUID.randomUUID())
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(10));
    }

    private static void assertEquals(Object expected, Object actual, String field) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(field + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test")));
        }
    }
}
