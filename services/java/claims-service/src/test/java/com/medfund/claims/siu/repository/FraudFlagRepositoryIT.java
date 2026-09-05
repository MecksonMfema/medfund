package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.FraudFlag;
import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 19 §A Phase 2 — repository IT for {@link FraudFlagRepository}. Uses
 * the test-migration public-schema baseline (V001 + V002 + V003) so the SIU
 * DDL from V169 lands via V003__siu_it.sql.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true",
        "fraud.retention.enabled=false"
})
@Import(FraudFlagRepositoryIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class FraudFlagRepositoryIT extends AbstractPostgresIntegrationTest {

    private static final UUID CLAIM_MEMBER_A = UUID.randomUUID();
    private static final UUID CLAIM_MEMBER_B = UUID.randomUUID();
    private static final UUID PROVIDER_A     = UUID.randomUUID();
    private static final UUID PROVIDER_B     = UUID.randomUUID();

    @Autowired private FraudFlagRepository repo;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void clean() {
        // Order matters: fraud_flag first (FK to siu_case can be dropped without cascade).
        db.sql("DELETE FROM fraud_flag").fetch().rowsUpdated().block(Duration.ofSeconds(5));
        db.sql("DELETE FROM claims").fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void save_and_findAllByClaimIdOrderByFlaggedAtDesc_orderedNewestFirst() {
        UUID claimId = seedClaim(CLAIM_MEMBER_A, PROVIDER_A);
        FraudFlag older  = aiFlag(claimId, OffsetDateTime.now().minusHours(2), "HIGH", "0.900");
        FraudFlag newer  = aiFlag(claimId, OffsetDateTime.now().minusMinutes(5), "MEDIUM", "0.600");

        repo.save(older).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        repo.save(newer).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        StepVerifier.create(repo.findAllByClaimIdOrderByFlaggedAtDesc(claimId)
                        .contextWrite(TenantTestContext.put()))
                .expectNextMatches(f -> "MEDIUM".equals(f.getRiskLevel()))
                .expectNextMatches(f -> "HIGH".equals(f.getRiskLevel()))
                .verifyComplete();
    }

    @Test
    void countHighRiskForMemberSince_countsOnlyHighRiskInWindowForMember() {
        UUID claimA1 = seedClaim(CLAIM_MEMBER_A, PROVIDER_A);
        UUID claimA2 = seedClaim(CLAIM_MEMBER_A, PROVIDER_B);
        UUID claimB1 = seedClaim(CLAIM_MEMBER_B, PROVIDER_A);
        OffsetDateTime now = OffsetDateTime.now();

        // In-window HIGH for member A — counted (2 rows).
        repo.save(aiFlag(claimA1, now.minusDays(1),  "HIGH",   "0.900")).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        repo.save(aiFlag(claimA2, now.minusDays(30), "HIGH",   "0.910")).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        // In-window MEDIUM — not counted.
        repo.save(aiFlag(claimA1, now.minusDays(2),  "MEDIUM", "0.610")).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        // Out-of-window HIGH — not counted.
        repo.save(aiFlag(claimA1, now.minusDays(120), "HIGH",  "0.930")).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        // HIGH for member B — not counted for A.
        repo.save(aiFlag(claimB1, now.minusDays(1),  "HIGH",   "0.940")).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        Long count = repo.countHighRiskForMemberSince(CLAIM_MEMBER_A, now.minusDays(90))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));

        assertThat(count).isEqualTo(2L);
    }

    @Test
    void countHighRiskForProviderSince_countsOnlyHighRiskInWindowForProvider() {
        UUID claimA1 = seedClaim(CLAIM_MEMBER_A, PROVIDER_A);
        UUID claimB1 = seedClaim(CLAIM_MEMBER_B, PROVIDER_A);
        UUID claimA2 = seedClaim(CLAIM_MEMBER_A, PROVIDER_B);
        OffsetDateTime now = OffsetDateTime.now();

        repo.save(aiFlag(claimA1, now.minusDays(1),  "HIGH", "0.900")).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        repo.save(aiFlag(claimB1, now.minusDays(2),  "HIGH", "0.910")).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        repo.save(aiFlag(claimA2, now.minusDays(3),  "HIGH", "0.920")).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        Long providerA = repo.countHighRiskForProviderSince(PROVIDER_A, now.minusDays(90))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        assertThat(providerA).isEqualTo(2L);
    }

    @Test
    void purgeUnlinkedOlderThan_removesOldUnlinkedKeepsFreshAndKeepsLinked() {
        // Cutoff strictly `<` in the DELETE, so we use ±30-day margins on the
        // 1-year horizon rather than sub-second edges to sidestep the
        // nanosecond-precision truncation R2DBC applies on the way to Postgres.
        UUID claimId = seedClaim(CLAIM_MEMBER_A, PROVIDER_A);
        UUID caseId = insertMinimalSiuCase();
        OffsetDateTime cutoff = OffsetDateTime.now().minus(1, ChronoUnit.YEARS);

        // Old + unlinked → purged.
        FraudFlag oldUnlinked = aiFlag(claimId, cutoff.minusDays(30), "HIGH", "0.910");
        // Fresh + unlinked → kept.
        FraudFlag freshUnlinked = aiFlag(claimId, cutoff.plusDays(30), "LOW", "0.150");
        // Old but linked to a case → kept regardless of age.
        FraudFlag oldLinked = aiFlag(claimId, cutoff.minusDays(60), "HIGH", "0.930");
        oldLinked.setSiuCaseId(caseId);

        FraudFlag savedOldUnlinked   = repo.save(oldUnlinked).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        FraudFlag savedFreshUnlinked = repo.save(freshUnlinked).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        FraudFlag savedOldLinked     = repo.save(oldLinked).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        Long deleted = repo.purgeUnlinkedOlderThan(cutoff)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));

        assertThat(deleted).as("only the old + unlinked row deleted").isEqualTo(1L);
        assertThat(repo.findById(savedOldUnlinked.getId()).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5)))
                .as("old unlinked row purged").isNull();
        assertThat(repo.findById(savedFreshUnlinked.getId()).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5)))
                .as("fresh unlinked row kept").isNotNull();
        assertThat(repo.findById(savedOldLinked.getId()).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5)))
                .as("old linked row kept").isNotNull();
    }

    // ── Seed helpers ─────────────────────────────────────────────────────

    private UUID seedClaim(UUID memberId, UUID providerId) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO claims (id, claim_number, member_id, provider_id, scheme_id, status,
                                    currency_code, claimed_amount, service_date)
                VALUES (:id, :cn, :mid, :pid, gen_random_uuid(), 'DRAFT', 'USD', 100, CURRENT_DATE)
                """)
                .bind("id", id).bind("cn", "CLM-" + id.toString().substring(0, 8))
                .bind("mid", memberId).bind("pid", providerId)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        return id;
    }

    private UUID insertMinimalSiuCase() {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO siu_case (id, case_number, status, opened_by, opened_by_email, opened_at)
                VALUES (:id, :cn, 'OPEN', :by, :email, NOW())
                """)
                .bind("id", id)
                .bind("cn", "SIU-IT-" + id.toString().substring(0, 8))
                .bind("by", UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .bind("email", "system@medfund.local")
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        return id;
    }

    private FraudFlag aiFlag(UUID claimId, OffsetDateTime flaggedAt, String level, String score) {
        FraudFlag f = new FraudFlag();
        f.setClaimId(claimId);
        f.setFlagSource("AI_MODEL");
        f.setModelVersion("fraud-isolation-forest-vTest");
        f.setRiskLevel(level);
        f.setRiskScore(new BigDecimal(score));
        f.setIndicators(Json.of("[]"));
        f.setFlaggedAt(flaggedAt);
        return f;
    }

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "it", "iss", "it", "email", "it@medfund.local")));
        }
    }
}
