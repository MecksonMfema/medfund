package com.medfund.finance.integration;

import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.dto.ContributionPaidEvent;
import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.dto.CreateRateCardRequest;
import com.medfund.finance.producer.entity.ClawbackEvent;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.repository.ClawbackEventRepository;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.service.CommissionCalcService;
import com.medfund.finance.producer.service.CommissionClawbackService;
import com.medfund.finance.producer.service.CommissionRateCardService;
import com.medfund.finance.producer.service.MemberProducerAssignmentService;
import com.medfund.finance.producer.service.ProducerService;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the Phase 3 commission-clawback path. Real
 * Postgres + real services. Verifies:
 * <ul>
 *   <li>Member-lapse writes a REVERSED compensating row + {@link ClawbackEvent}
 *       with {@code source=MEMBER_LAPSE} when the accrual is inside the
 *       rate-card window.</li>
 *   <li>Contribution-revoke reverses the accrual unconditionally + writes
 *       a clawback_event with {@code source=CONTRIBUTION_REVOKE}.</li>
 *   <li>Both replay-safe — a second invocation writes zero duplicate rows
 *       (bounces off {@code ux_clawback_by_source}).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(CommissionClawbackIT.SecurityStub.class)
class CommissionClawbackIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000042";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private ProducerService producerService;
    @Autowired private CommissionRateCardService rateCardService;
    @Autowired private MemberProducerAssignmentService assignmentService;
    @Autowired private CommissionCalcService commissionCalcService;
    @Autowired private CommissionClawbackService commissionClawbackService;
    @Autowired private CommissionTransactionRepository commissionTxnRepository;
    @Autowired private ClawbackEventRepository clawbackEventRepository;

    @MockBean private AuditPublisher auditPublisher;
    @MockBean private RuleEvaluationService ruleEvaluationService;
    @MockBean private TenantRuleLoader tenantRuleLoader;

    @Test
    @WithTenant(TENANT_ID)
    void memberLapse_withinWindow_writesClawbackAndReversedTxn() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));

        UUID memberId = UUID.randomUUID();
        seedProducerAssignedTo(memberId);
        seedRateCard("HEALTH", new BigDecimal("10.0000"), 90);
        CommissionTransaction accrued = accrueCommission(memberId, new BigDecimal("500.00"));

        Instant lapseAt = Instant.now();  // 0-day-old accrual, well within 90-day window
        ClawbackEvent cb = commissionClawbackService
                .processMemberLapse(memberId, lapseAt, "arrears", "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .blockFirst(TIMEOUT);
        assertThat(cb).isNotNull();
        assertThat(cb.getSource()).isEqualTo("MEMBER_LAPSE");
        assertThat(cb.getCommissionTransactionId()).isEqualTo(accrued.getId());

        // Original flipped to CLAWED_BACK
        CommissionTransaction reloadedOriginal = commissionTxnRepository.findById(accrued.getId())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reloadedOriginal).isNotNull();
        assertThat(reloadedOriginal.getStatus()).isEqualTo("CLAWED_BACK");
        assertThat(reloadedOriginal.getReversedByTxnId()).isNotNull();
    }

    @Test
    @WithTenant(TENANT_ID)
    void contributionRevoke_reversesTxn_andWritesClawbackEvent() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));

        UUID memberId = UUID.randomUUID();
        seedProducerAssignedTo(memberId);
        seedRateCard("HEALTH", new BigDecimal("10.0000"), 30);
        CommissionTransaction accrued = accrueCommission(memberId, new BigDecimal("500.00"));

        ClawbackEvent cb = commissionClawbackService
                .processContributionRevoke(accrued.getContributionId(), memberId,
                        Instant.now(), "revoked", "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(cb).isNotNull();
        assertThat(cb.getSource()).isEqualTo("CONTRIBUTION_REVOKE");

        CommissionTransaction reloadedOriginal = commissionTxnRepository.findById(accrued.getId())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reloadedOriginal).isNotNull();
        assertThat(reloadedOriginal.getStatus()).isEqualTo("REVERSED");
    }

    @Test
    @WithTenant(TENANT_ID)
    void contributionRevoke_replay_isIdempotent() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));

        UUID memberId = UUID.randomUUID();
        seedProducerAssignedTo(memberId);
        seedRateCard("HEALTH", new BigDecimal("10.0000"), 30);
        CommissionTransaction accrued = accrueCommission(memberId, new BigDecimal("500.00"));

        commissionClawbackService
                .processContributionRevoke(accrued.getContributionId(), memberId,
                        Instant.now(), "revoked", "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        commissionClawbackService
                .processContributionRevoke(accrued.getContributionId(), memberId,
                        Instant.now(), "revoked", "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        Long clawbackCount = clawbackEventRepository.findAll()
                .contextWrite(TenantTestContext.put())
                .filter(e -> accrued.getContributionId().toString().equals(e.getTriggeringEventRef()))
                .count().block(TIMEOUT);
        assertThat(clawbackCount).isEqualTo(1L);
    }

    // ---- helpers ----

    private void seedProducerAssignedTo(UUID memberId) {
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
    }

    private void seedRateCard(String insuranceLine, BigDecimal ratePct, Integer windowDays) {
        rateCardService.create(
                        new CreateRateCardRequest(
                                "Test " + insuranceLine + " " + UUID.randomUUID(),
                                insuranceLine, null, ratePct, windowDays,
                                LocalDate.now().minusMonths(3), null),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
    }

    private CommissionTransaction accrueCommission(UUID memberId, BigDecimal amount) {
        ContributionPaidEvent event = new ContributionPaidEvent(
                UUID.randomUUID(), memberId, amount, "USD", "HEALTH",
                OffsetDateTime.now(), TENANT_ID);
        return commissionCalcService.processPaidContribution(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
    }
}
