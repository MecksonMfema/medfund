package com.medfund.finance.integration;

import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.dto.ContributionPaidEvent;
import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.dto.CreateRateCardRequest;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.service.CommissionCalcService;
import com.medfund.finance.producer.service.CommissionRateCardService;
import com.medfund.finance.producer.service.MemberProducerAssignmentService;
import com.medfund.finance.producer.service.ProducerService;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the Phase 3 commission-accrual path. Real
 * Postgres (Testcontainers) + real R2DBC + real services. Rules engine
 * mocked to keep the harness focused on the persistence + audit flow;
 * DRL compilation is exercised by {@code DrlCompilerTest} unit-level.
 *
 * <p>Verifies:
 * <ul>
 *   <li>A paid contribution writes exactly one {@code commission_transaction}
 *       with the correct amount, currency, reference and status={@code ACCRUED}.</li>
 *   <li>The AuditEvent carries the friendly {@code reference} as
 *       {@code entityName} (feedback_audit_entity_name).</li>
 *   <li>Reprocessing the same contribution is idempotent — the partial
 *       UNIQUE index {@code ux_commission_txn_source} bounces the duplicate
 *       and the row count stays at 1.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(CommissionCalcIT.SecurityStub.class)
class CommissionCalcIT extends AbstractIntegrationTest {

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

    @Autowired private ProducerService producerService;
    @Autowired private CommissionRateCardService rateCardService;
    @Autowired private MemberProducerAssignmentService assignmentService;
    @Autowired private CommissionCalcService commissionCalcService;
    @Autowired private CommissionTransactionRepository commissionTxnRepository;

    @MockBean private AuditPublisher auditPublisher;
    @MockBean private RuleEvaluationService ruleEvaluationService;
    @MockBean private TenantRuleLoader tenantRuleLoader;

    @Test
    @WithTenant(TENANT_ID)
    void paidContribution_writesCommissionTransaction_withAudit() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));

        UUID memberId = UUID.randomUUID();
        UUID producerId = seedProducerAssignedTo(memberId);
        seedRateCard("HEALTH", new BigDecimal("10.0000"));

        UUID contributionId = UUID.randomUUID();
        ContributionPaidEvent event = new ContributionPaidEvent(
                contributionId, memberId,
                new BigDecimal("500.00"), "USD", "HEALTH",
                OffsetDateTime.now(), TENANT_ID);

        CommissionTransaction saved = commissionCalcService
                .processPaidContribution(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(saved).isNotNull();
        assertThat(saved.getProducerId()).isEqualTo(producerId);
        assertThat(saved.getContributionId()).isEqualTo(contributionId);
        assertThat(saved.getMemberId()).isEqualTo(memberId);
        assertThat(saved.getInsuranceLine()).isEqualTo("HEALTH");
        // Base = 500 * 10.0000 / 100 = 50.0000
        assertThat(saved.getNativeAmount()).isEqualByComparingTo("50.0000");
        assertThat(saved.getNativeCurrency()).isEqualTo("USD");
        assertThat(saved.getStatus()).isEqualTo("ACCRUED");
        assertThat(saved.getReference()).startsWith("COMM-");

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, atLeastOnce()).publish(cap.capture());
        assertThat(cap.getAllValues()).extracting(AuditEvent::entityType).contains("CommissionTransaction");
        assertThat(cap.getAllValues()).allSatisfy(ev -> {
            assertThat(ev.entityName()).doesNotContain(ev.entityId());  // friendly, not UUID
            assertThat(ev.actorEmail()).isNotBlank();
        });
    }

    @Test
    @WithTenant(TENANT_ID)
    void reprocessingSameContribution_isIdempotent() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));

        UUID memberId = UUID.randomUUID();
        seedProducerAssignedTo(memberId);
        seedRateCard("HEALTH", new BigDecimal("10.0000"));

        UUID contributionId = UUID.randomUUID();
        ContributionPaidEvent event = new ContributionPaidEvent(
                contributionId, memberId,
                new BigDecimal("500.00"), "USD", "HEALTH",
                OffsetDateTime.now(), TENANT_ID);

        commissionCalcService.processPaidContribution(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        commissionCalcService.processPaidContribution(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        Long count = commissionTxnRepository.findAll()
                .contextWrite(TenantTestContext.put())
                .filter(t -> contributionId.equals(t.getContributionId()))
                .count().block(TIMEOUT);
        assertThat(count).isEqualTo(1L);
    }

    private UUID seedProducerAssignedTo(UUID memberId) {
        var producer = producerService.create(
                        new CreateProducerRequest(
                                "BRK-" + UUID.randomUUID().toString().substring(0, 8),
                                "Test Broker", null, null, "ZW", "USD",
                                null, null, null),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(producer).isNotNull();
        assignmentService.assign(memberId,
                        new AssignMemberRequest(producer.id(), LocalDate.now().withDayOfMonth(1), "test"),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        return producer.id();
    }

    private void seedRateCard(String insuranceLine, BigDecimal ratePct) {
        rateCardService.create(
                        new CreateRateCardRequest(
                                "Test " + insuranceLine + " " + ratePct,
                                insuranceLine, null, ratePct, null,
                                LocalDate.now().minusMonths(3), null),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
    }
}
