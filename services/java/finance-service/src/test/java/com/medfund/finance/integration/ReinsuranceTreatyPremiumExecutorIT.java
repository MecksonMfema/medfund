package com.medfund.finance.integration;

import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyApplicableLineRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.UpsertTreatyLayerRequest;
import com.medfund.finance.reinsurance.dto.UpsertTreatyParticipantRequest;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.job.ReinsuranceTreatyPremiumExecutor;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.service.ReinsurerService;
import com.medfund.finance.reinsurance.service.TreatyApplicableLineService;
import com.medfund.finance.reinsurance.service.TreatyLayerService;
import com.medfund.finance.reinsurance.service.TreatyParticipantService;
import com.medfund.finance.reinsurance.service.TreatyService;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the Phase 6 treaty premium executor. Real
 * Postgres via Testcontainers; the executor runs against seeded XoL /
 * StopLoss treaties and asserts one PREMIUM cession is written per
 * treaty, that a rerun is idempotent, and that proportional treaties
 * are excluded from this path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(ReinsuranceTreatyPremiumExecutorIT.SecurityStub.class)
class ReinsuranceTreatyPremiumExecutorIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000041";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private ReinsuranceTreatyPremiumExecutor executor;
    @Autowired private ReinsurerService reinsurerService;
    @Autowired private TreatyService treatyService;
    @Autowired private TreatyLayerService layerService;
    @Autowired private TreatyParticipantService participantService;
    @Autowired private TreatyApplicableLineService applicableLineService;
    @Autowired private CessionRepository cessionRepository;

    @MockBean private AuditPublisher auditPublisher;

    @Test
    @WithTenant(TENANT_ID)
    void execute_seededXolAndStopLoss_writesOnePremiumPerTreaty() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        UUID xolId = seedActiveNonProportionalTreaty("EXCESS_OF_LOSS",
                new BigDecimal("50000.00"), List.of("HEALTH"));
        UUID slId = seedActiveNonProportionalTreaty("STOP_LOSS",
                new BigDecimal("30000.00"), List.of("HEALTH"));

        executor.execute(TENANT_ID, "{}")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);

        Cession xolCession = cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        xolId, xolId, "PREMIUM")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(xolCession).isNotNull();
        assertThat(xolCession.getCededAmount()).isEqualByComparingTo("50000.00");
        assertThat(xolCession.getSourceEventType()).isEqualTo("TREATY_INCEPTION");

        Cession slCession = cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        slId, slId, "PREMIUM")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(slCession).isNotNull();
        assertThat(slCession.getCededAmount()).isEqualByComparingTo("30000.00");
    }

    @Test
    @WithTenant(TENANT_ID)
    void execute_rerun_isIdempotent() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        UUID xolId = seedActiveNonProportionalTreaty("EXCESS_OF_LOSS",
                new BigDecimal("50000.00"), List.of("HEALTH"));

        executor.execute(TENANT_ID, "{}").contextWrite(TenantTestContext.put()).block(TIMEOUT);
        executor.execute(TENANT_ID, "{}").contextWrite(TenantTestContext.put()).block(TIMEOUT);
        executor.execute(TENANT_ID, "{}").contextWrite(TenantTestContext.put()).block(TIMEOUT);

        Long count = cessionRepository.findBySourceEventId(xolId)
                .contextWrite(TenantTestContext.put())
                .count().block(TIMEOUT);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @WithTenant(TENANT_ID)
    void execute_proportionalTreatyExcluded_writesNothing() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        UUID qsId = seedActiveProportionalTreaty(new BigDecimal("20000.00"));

        executor.execute(TENANT_ID, "{}").contextWrite(TenantTestContext.put()).block(TIMEOUT);

        Long count = cessionRepository.findBySourceEventId(qsId)
                .contextWrite(TenantTestContext.put())
                .count().block(TIMEOUT);
        assertThat(count).isZero();
    }

    private UUID seedActiveNonProportionalTreaty(String treatyType, BigDecimal expectedPremium,
                                                 List<String> applicableLines) {
        var reinsurer = reinsurerService.create(
                        new CreateReinsurerRequest(
                                "XoL Re " + UUID.randomUUID(), null, null, null, null, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reinsurer).isNotNull();

        var draft = treatyService.createDraft(
                        new CreateTreatyRequest("XOL-" + UUID.randomUUID(),
                                treatyType, "USD",
                                LocalDate.now().minusDays(30),
                                LocalDate.now().plusDays(300),
                                null, null, expectedPremium, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draft).isNotNull();

        participantService.upsert(draft.id(),
                        new UpsertTreatyParticipantRequest(reinsurer.id(),
                                new BigDecimal("100.0000"), "LEADER"),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        for (String line : applicableLines) {
            applicableLineService.add(draft.id(),
                            new CreateTreatyApplicableLineRequest(line),
                            "sys", "system@medfund")
                    .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        }
        // Non-proportional treaties need at least one layer to activate.
        layerService.create(draft.id(),
                        new UpsertTreatyLayerRequest(
                                1, new BigDecimal("100000.00"), new BigDecimal("500000.00"),
                                "USD", new BigDecimal("0.05"), 1),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        treatyService.activate(draft.id(), "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        return draft.id();
    }

    private UUID seedActiveProportionalTreaty(BigDecimal expectedPremium) {
        var reinsurer = reinsurerService.create(
                        new CreateReinsurerRequest(
                                "QS Re " + UUID.randomUUID(), null, null, null, null, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reinsurer).isNotNull();

        var draft = treatyService.createDraft(
                        new CreateTreatyRequest("QS-" + UUID.randomUUID(),
                                "QUOTA_SHARE", "USD",
                                LocalDate.now().minusDays(30),
                                LocalDate.now().plusDays(300),
                                null, null, expectedPremium, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draft).isNotNull();

        participantService.upsert(draft.id(),
                        new UpsertTreatyParticipantRequest(reinsurer.id(),
                                new BigDecimal("100.0000"), "LEADER"),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        applicableLineService.add(draft.id(),
                        new CreateTreatyApplicableLineRequest("HEALTH"),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        treatyService.activate(draft.id(), "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        return draft.id();
    }
}
