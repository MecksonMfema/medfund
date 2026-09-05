package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.SiuCase;
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

import java.math.BigDecimal;
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
@Import(SiuCaseRepositoryIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class SiuCaseRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired private SiuCaseRepository repo;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM fraud_flag").fetch().rowsUpdated().block(Duration.ofSeconds(5));
        db.sql("DELETE FROM siu_case").fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void save_and_findById_roundTrip() {
        SiuCase kase = openCase("SIU-IT-1");
        kase.setPriority("HIGH");
        kase.setTags(new String[]{"provider-watchlist", "member-repeat"});

        SiuCase saved = repo.save(kase).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        SiuCase loaded = repo.findById(saved.getId())
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCaseNumber()).isEqualTo("SIU-IT-1");
        assertThat(loaded.getStatus()).isEqualTo("OPEN");
        assertThat(loaded.getPriority()).isEqualTo("HIGH");
        assertThat(loaded.getTags()).containsExactly("provider-watchlist", "member-repeat");
    }

    @Test
    void findAllByStatusOrderByOpenedAtDesc_orderedNewestFirst() {
        SiuCase older = openCase("SIU-IT-OLDER");
        older.setOpenedAt(OffsetDateTime.now().minusHours(4));
        SiuCase newer = openCase("SIU-IT-NEWER");
        newer.setOpenedAt(OffsetDateTime.now().minusMinutes(2));

        repo.save(older).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        repo.save(newer).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        List<SiuCase> ordered = repo.findAllByStatusOrderByOpenedAtDesc("OPEN")
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(ordered).extracting(SiuCase::getCaseNumber)
                .containsExactly("SIU-IT-NEWER", "SIU-IT-OLDER");
    }

    @Test
    void closeConfirmed_persistsSavingsAmountAndCurrency() {
        SiuCase kase = openCase("SIU-IT-CLOSE");
        SiuCase saved = repo.save(kase).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        saved.setStatus("CLOSED_CONFIRMED_FRAUD");
        saved.setOutcome("CONFIRMED_FRAUD");
        saved.setClosedBy(UUID.randomUUID());
        saved.setClosedByEmail("supervisor@medfund.local");
        saved.setClosedAt(OffsetDateTime.now());
        saved.setSavedAmount(new BigDecimal("1500.0000"));
        saved.setSavedCurrency("USD");
        saved.setClosureReason("Duplicate provider submission");
        saved.setUpdatedAt(OffsetDateTime.now());
        repo.save(saved).contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));

        SiuCase loaded = repo.findById(saved.getId())
                .contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo("CLOSED_CONFIRMED_FRAUD");
        assertThat(loaded.getSavedAmount()).isEqualByComparingTo("1500.0000");
        assertThat(loaded.getSavedCurrency()).isEqualTo("USD");
    }

    private SiuCase openCase(String caseNumber) {
        OffsetDateTime now = OffsetDateTime.now();
        SiuCase k = new SiuCase();
        k.setCaseNumber(caseNumber);
        k.setStatus("OPEN");
        k.setOpenedBy(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        k.setOpenedByEmail("system@medfund.local");
        k.setOpenedAt(now);
        // Populate the audit columns so the round-trip UPDATE doesn't NULL
        // them out (see note on SiuCase.createdAt/updatedAt).
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        return k;
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
