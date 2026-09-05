package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.SiuCase;
import com.medfund.claims.siu.entity.SiuCaseNote;
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
@Import(SiuCaseNoteRepositoryIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class SiuCaseNoteRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired private SiuCaseRepository caseRepo;
    @Autowired private SiuCaseNoteRepository noteRepo;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM siu_case_note").fetch().rowsUpdated().block(Duration.ofSeconds(5));
        db.sql("DELETE FROM fraud_flag").fetch().rowsUpdated().block(Duration.ofSeconds(5));
        db.sql("DELETE FROM siu_case").fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void findAllByCaseIdOrderByCreatedAtAsc_returnsChronologically() {
        UUID caseId = openCase();

        SiuCaseNote first  = note(caseId, "STATUS_CHANGE", "OPEN → UNDER_REVIEW",
                OffsetDateTime.now().minusMinutes(30));
        SiuCaseNote second = note(caseId, "COMMENT", "Requested provider records",
                OffsetDateTime.now().minusMinutes(20));
        SiuCaseNote third  = note(caseId, "EVIDENCE_ADDED", "Uploaded PDF",
                OffsetDateTime.now().minusMinutes(10));

        // Insert out of order to prove the ORDER BY is doing the work.
        noteRepo.save(third).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        noteRepo.save(first).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        noteRepo.save(second).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        List<SiuCaseNote> chronological = noteRepo.findAllByCaseIdOrderByCreatedAtAsc(caseId)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chronological).extracting(SiuCaseNote::getNoteType)
                .containsExactly("STATUS_CHANGE", "COMMENT", "EVIDENCE_ADDED");
    }

    @Test
    void save_populatesGeneratedIdAndPersistsCreatedAt() {
        UUID caseId = openCase();
        SiuCaseNote n = new SiuCaseNote();
        n.setCaseId(caseId);
        n.setAuthorId(UUID.randomUUID());
        n.setAuthorEmail("officer@medfund.local");
        n.setNoteType("COMMENT");
        n.setBody("First narrative note on the case");
        n.setCreatedAt(OffsetDateTime.now());

        SiuCaseNote saved = noteRepo.save(n).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        // Re-fetch to confirm the row landed and createdAt persisted through
        // the DB round-trip (R2DBC's returned entity doesn't observe DB
        // DEFAULT columns, so we verify via findById instead).
        SiuCaseNote loaded = noteRepo.findById(saved.getId())
                .contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    private UUID openCase() {
        OffsetDateTime now = OffsetDateTime.now();
        SiuCase k = new SiuCase();
        k.setCaseNumber("SIU-NOTE-" + UUID.randomUUID().toString().substring(0, 8));
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

    private SiuCaseNote note(UUID caseId, String type, String body, OffsetDateTime createdAt) {
        SiuCaseNote n = new SiuCaseNote();
        n.setCaseId(caseId);
        n.setAuthorId(UUID.randomUUID());
        n.setAuthorEmail("officer@medfund.local");
        n.setNoteType(type);
        n.setBody(body);
        n.setCreatedAt(createdAt);
        return n;
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
