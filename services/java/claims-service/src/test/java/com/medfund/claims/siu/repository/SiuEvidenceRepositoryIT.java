package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.SiuCase;
import com.medfund.claims.siu.entity.SiuEvidence;
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
@Import(SiuEvidenceRepositoryIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class SiuEvidenceRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired private SiuCaseRepository caseRepo;
    @Autowired private SiuEvidenceRepository repo;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM siu_evidence").fetch().rowsUpdated().block(Duration.ofSeconds(5));
        db.sql("DELETE FROM siu_case").fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void save_and_findAllByCaseId_returnsNewestFirst() {
        UUID caseId = openCase();

        SiuEvidence a = evidence(caseId, "s3://a", "First doc",   "DOCUMENT",
                OffsetDateTime.now().minusHours(2));
        SiuEvidence b = evidence(caseId, "s3://b", "Second photo","PHOTO",
                OffsetDateTime.now().minusHours(1));
        SiuEvidence c = evidence(caseId, "s3://c", "Provider dump","PROVIDER_RECORD",
                OffsetDateTime.now());

        // Insert out of order to prove the ORDER BY does the work.
        repo.save(a).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        repo.save(c).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        repo.save(b).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        List<SiuEvidence> newestFirst = repo.findAllByCaseIdOrderByUploadedAtDesc(caseId)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(newestFirst).extracting(SiuEvidence::getFileServiceRef)
                .containsExactly("s3://c", "s3://b", "s3://a");
    }

    @Test
    void save_populatesIdAndPreservesFields() {
        UUID caseId = openCase();
        SiuEvidence ev = evidence(caseId, "s3://x", "Uploaded PDF", "DOCUMENT",
                OffsetDateTime.now());
        SiuEvidence saved = repo.save(ev).contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        SiuEvidence loaded = repo.findById(saved.getId())
                .contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getDescription()).isEqualTo("Uploaded PDF");
        assertThat(loaded.getEvidenceType()).isEqualTo("DOCUMENT");
        assertThat(loaded.getUploadedByEmail()).isEqualTo("officer@medfund.local");
        assertThat(loaded.getUploadedAt()).isNotNull();
    }

    private UUID openCase() {
        OffsetDateTime now = OffsetDateTime.now();
        SiuCase k = new SiuCase();
        k.setCaseNumber("SIU-EV-" + UUID.randomUUID().toString().substring(0, 8));
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

    private SiuEvidence evidence(UUID caseId, String ref, String desc, String type,
                                  OffsetDateTime uploadedAt) {
        SiuEvidence ev = new SiuEvidence();
        ev.setCaseId(caseId);
        ev.setFileServiceRef(ref);
        ev.setDescription(desc);
        ev.setEvidenceType(type);
        ev.setUploadedBy(UUID.randomUUID());
        ev.setUploadedByEmail("officer@medfund.local");
        ev.setUploadedAt(uploadedAt);
        return ev;
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
