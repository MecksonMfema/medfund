package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.SiuCase;
import com.medfund.claims.siu.entity.SiuReferral;
import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
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

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true",
        "fraud.retention.enabled=false"
})
@Import(SiuReferralRepositoryIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class SiuReferralRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired private SiuCaseRepository caseRepo;
    @Autowired private SiuReferralRepository repo;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM siu_referral").fetch().rowsUpdated().block(Duration.ofSeconds(5));
        db.sql("DELETE FROM siu_case").fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void save_and_findAllByCaseId_returnsNewestFirst() {
        UUID caseId = openCase();

        SiuReferral r1 = referral(caseId, "LAW_ENFORCEMENT", "ZRP-1",
                OffsetDateTime.now().minusHours(3));
        SiuReferral r2 = referral(caseId, "REGULATOR", "IPEC-2",
                OffsetDateTime.now().minusHours(1));

        repo.save(r1).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        repo.save(r2).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        List<SiuReferral> newestFirst = repo.findAllByCaseIdOrderByReferredAtDesc(caseId)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(newestFirst).extracting(SiuReferral::getReferralReference)
                .containsExactly("IPEC-2", "ZRP-1");
    }

    @Test
    void save_nullReference_persistsCleanly() {
        UUID caseId = openCase();
        SiuReferral r = referral(caseId, "INTERNAL_HR", null, OffsetDateTime.now());

        SiuReferral saved = repo.save(r).contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();

        SiuReferral loaded = repo.findById(saved.getId())
                .contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getReferralTo()).isEqualTo("INTERNAL_HR");
        assertThat(loaded.getReferralReference()).isNull();
    }

    private UUID openCase() {
        OffsetDateTime now = OffsetDateTime.now();
        SiuCase k = new SiuCase();
        k.setCaseNumber("SIU-REF-" + UUID.randomUUID().toString().substring(0, 8));
        k.setStatus("OPEN");
        k.setOpenedBy(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        k.setOpenedByEmail("system@medfund.local");
        k.setOpenedAt(now);
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        return caseRepo.save(k).contextWrite(TenantTestContext.put())
                .map(SiuCase::getId)
                .block(Duration.ofSeconds(5));
    }

    private SiuReferral referral(UUID caseId, String to, String ref,
                                  OffsetDateTime referredAt) {
        SiuReferral r = new SiuReferral();
        r.setCaseId(caseId);
        r.setReferralTo(to);
        r.setReferralReference(ref);
        r.setReferredBy(UUID.randomUUID());
        r.setReferredByEmail("supervisor@medfund.local");
        r.setReferredAt(referredAt);
        return r;
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
