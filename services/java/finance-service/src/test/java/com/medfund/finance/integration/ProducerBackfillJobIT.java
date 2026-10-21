package com.medfund.finance.integration;

import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.entity.ProducerBackfillCandidate;
import com.medfund.finance.producer.repository.ProducerBackfillCandidateRepository;
import com.medfund.finance.producer.service.ProducerBackfillJob;
import com.medfund.finance.producer.service.ProducerBackfillReviewService;
import com.medfund.finance.producer.service.ProducerService;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the producer treaty backfill (Phase 10 §B). Real
 * Postgres + real R2DBC + real service layer; only AuditPublisher is mocked.
 * Covers the three plan-specified integration cases: seeded auto-accept mix,
 * idempotent rerun, and accept-updates-treaty-plus-siblings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(ProducerBackfillJobIT.SecurityStub.class)
class ProducerBackfillJobIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000030";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired private ProducerService producerService;
    @Autowired private ProducerBackfillJob backfillJob;
    @Autowired private ProducerBackfillReviewService reviewService;
    @Autowired private TreatyRepository treatyRepository;
    @Autowired private ProducerBackfillCandidateRepository candidateRepository;
    @MockBean private AuditPublisher auditPublisher;

    @Test
    @WithTenant(TENANT_ID)
    void runBackfill_seededMix_writesExpectedCandidatesAndAutoAccepts() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        var alpha = producerService.create(pReq("BRK-A-IT30", "Alpha Brokers Zim"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        var beta = producerService.create(pReq("BRK-B-IT30", "Beta Insurance Services"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        producerService.create(pReq("BRK-C-IT30", "Gamma Underwriting"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        // Seed three treaties: one exact-match (auto-accept), one near (below
        // 0.900 threshold — must queue PENDING), one gibberish (below 0.500 min).
        Treaty exact  = seedTreatyDirect("TR-EXACT-IT30", "Alpha Brokers Zim");
        Treaty near   = seedTreatyDirect("TR-NEAR-IT30",  "Beta Insurance");     // ≈0.609 vs "Beta Insurance Services"
        Treaty gibber = seedTreatyDirect("TR-NONE-IT30",  "xxxx-no-such-broker-xxxx");

        backfillJob.runBackfill("sys", "admin@test.example")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);

        // Exact-match treaty → producer_id set, candidate ACCEPTED
        Treaty exactReloaded = treatyRepository.findById(exact.getId())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(exactReloaded).isNotNull();
        assertThat(exactReloaded.getProducerId()).isEqualTo(alpha.id());

        ProducerBackfillCandidate exactCand = candidateRepository
                .findByTreatyAndCandidate(exact.getId(), alpha.id())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(exactCand).isNotNull();
        assertThat(exactCand.getStatus()).isEqualTo("ACCEPTED");
        assertThat(exactCand.getConfidenceScore()).isGreaterThanOrEqualTo(new java.math.BigDecimal("0.900"));

        // Near-match treaty → producer_id NULL, candidate PENDING (score < 0.900)
        Treaty nearReloaded = treatyRepository.findById(near.getId())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        ProducerBackfillCandidate nearCand = candidateRepository
                .findByTreatyAndCandidate(near.getId(), beta.id())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(nearReloaded.getProducerId()).isNull();
        assertThat(nearCand).isNotNull();
        assertThat(nearCand.getStatus()).isEqualTo("PENDING");

        // Gibberish-ref treaty → no candidate at all
        Long candForGibber = candidateRepository.findByStatusOrderByConfidenceScoreDesc(
                "PENDING", 0, 100)
                .filter(c -> gibber.getId().equals(c.getTreatyId()))
                .count()
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(candForGibber).isZero();
    }

    @Test
    @WithTenant(TENANT_ID)
    void runBackfill_rerun_writesZeroDuplicates() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        producerService.create(pReq("BRK-IDEM-IT30", "Idempotent Brokers"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        Treaty t = seedTreatyDirect("TR-IDEM-IT30", "Idempotant Broker"); // near-match

        backfillJob.runBackfill("sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        Long firstCount = candidateRepository.findByStatusOrderByConfidenceScoreDesc("PENDING", 0, 100)
                .filter(c -> t.getId().equals(c.getTreatyId()))
                .count()
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        // Rerun — the ux_pbc_treaty_candidate UNIQUE index prevents dupes.
        backfillJob.runBackfill("sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        Long secondCount = candidateRepository.findByStatusOrderByConfidenceScoreDesc("PENDING", 0, 100)
                .filter(c -> t.getId().equals(c.getTreatyId()))
                .count()
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    @WithTenant(TENANT_ID)
    void accept_updatesTreatyAndRejectsSiblings() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        // Two producers whose names differ enough from the ref to score in the
        // 0.5..0.9 band. "Zebra Ltd" (9 chars) → distance 4 to each 13-char
        // sibling → similarity 1 - 4/13 = 0.692 → both PENDING (same score,
        // sort tiebreaks by name alphabetical).
        var suspectOne = producerService.create(pReq("BRK-ONE-IT30", "Zebra One Ltd"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        var suspectTwo = producerService.create(pReq("BRK-TWO-IT30", "Zebra Two Ltd"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        Treaty t = seedTreatyDirect("TR-ACCEPT-IT30", "Zebra Ltd");

        backfillJob.runBackfill("sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        List<ProducerBackfillCandidate> pending = candidateRepository
                .findByStatusOrderByConfidenceScoreDesc("PENDING", 0, 100)
                .filter(c -> t.getId().equals(c.getTreatyId()))
                .collectList()
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(pending).hasSizeGreaterThanOrEqualTo(2);

        UUID acceptedCandidateId = pending.get(0).getId();
        UUID acceptedProducerId  = pending.get(0).getCandidateProducerId();

        reviewService.accept(acceptedCandidateId, UUID.randomUUID().toString(), "reviewer@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        Treaty reloaded = treatyRepository.findById(t.getId())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reloaded.getProducerId()).isEqualTo(acceptedProducerId);

        ProducerBackfillCandidate acceptedRow = candidateRepository.findById(acceptedCandidateId)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(acceptedRow.getStatus()).isEqualTo("ACCEPTED");

        List<ProducerBackfillCandidate> remaining = candidateRepository
                .findByStatusOrderByConfidenceScoreDesc("PENDING", 0, 100)
                .filter(c -> t.getId().equals(c.getTreatyId()))
                .collectList()
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(remaining).isEmpty();

        // Silences unused-variable warning on the second suspect — used only to
        // guarantee > 1 pending candidate for the same treaty.
        assertThat(suspectOne.id()).isNotEqualTo(suspectTwo.id());
    }

    // ── seed helpers ─────────────────────────────────────────────────────

    private CreateProducerRequest pReq(String code, String name) {
        return new CreateProducerRequest(code, name, null, null, "ZW", "USD", null, null, null);
    }

    /**
     * Insert a Treaty directly via the repository so we can carry a
     * {@code producer_ref} without running the full DRAFT → ACTIVE lifecycle.
     */
    private Treaty seedTreatyDirect(String ref, String producerRef) {
        Treaty t = new Treaty();
        t.setTreatyRef(ref);
        t.setTreatyType("QUOTA_SHARE");
        t.setDeclaredCurrency("USD");
        t.setInceptionDate(LocalDate.of(2026, 1, 1));
        t.setExpiryDate(LocalDate.of(2027, 1, 1));
        t.setStatus("DRAFT");
        t.setProducerRef(producerRef);
        t.setCreatedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        return treatyRepository.save(t)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
    }
}
