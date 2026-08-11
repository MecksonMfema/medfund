package com.medfund.claims.service;

import com.medfund.claims.client.MemberLookupClient;
import com.medfund.claims.client.MemberLookupClient.MemberSummary;
import com.medfund.claims.costshare.CostShareConfig;
import com.medfund.claims.costshare.MemberCostShareAccumulatorReader;
import com.medfund.claims.costshare.SchemeCostShareReader;
import com.medfund.claims.dto.AdjudicationResult.CostShareBreakdown;
import com.medfund.claims.dto.AdjudicationResult.StageResult;
import com.medfund.claims.dto.EligibilityQuoteRequest;
import com.medfund.claims.dto.EligibilityQuoteResponse;
import com.medfund.claims.service.AdjudicationPipeline.DryRunResult;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EligibilityQuoteService}. Mocks the pipeline +
 * calculator + readers so the service's own logic (coverage classification,
 * remaining-bucket math, audit shape) is what's exercised.
 */
class EligibilityQuoteServiceTest {

    private MemberLookupClient memberLookupClient;
    private AdjudicationPipeline pipeline;
    private CostShareCalculator costShareCalculator;
    private SchemeCostShareReader schemeReader;
    private MemberCostShareAccumulatorReader accReader;
    private AuditPublisher auditPublisher;
    private EligibilityQuoteService service;

    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final UUID SCHEME_ID = UUID.randomUUID();
    private static final UUID PROVIDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        memberLookupClient = mock(MemberLookupClient.class);
        pipeline = mock(AdjudicationPipeline.class);
        costShareCalculator = mock(CostShareCalculator.class);
        schemeReader = mock(SchemeCostShareReader.class);
        accReader = mock(MemberCostShareAccumulatorReader.class);
        auditPublisher = mock(AuditPublisher.class);

        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(pipeline.dryRun(any(), any())).thenReturn(Mono.just(new DryRunResult(
                List.of(new StageResult("Eligibility", true, "ok"),
                        new StageResult("TenantRules", true, "no rules")),
                com.medfund.claims.dto.AiSignals.empty(),
                new BigDecimal("500"), List.of())));
        when(costShareCalculator.compute(any(), any(), any(), any()))
                .thenReturn(Mono.just(breakdown(
                        "500", "100", "25", "0", "0", "0", "125")));
        when(schemeReader.findEffective(any(), anyInt(), any()))
                .thenReturn(Mono.just(new CostShareConfig.Scheme(
                        UUID.randomUUID(), SCHEME_ID, 2026,
                        new BigDecimal("500"), new BigDecimal("2000"),
                        "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER",
                        "USD", LocalDate.of(2026, 1, 1), null)));
        when(accReader.findFor(any(), any(), any(), anyInt()))
                .thenReturn(Mono.just(new CostShareConfig.Accumulator(
                        UUID.randomUUID(), MEMBER_ID, null, SCHEME_ID, 2026,
                        new BigDecimal("100"), new BigDecimal("125"), 0, "USD", 0)));

        service = new EligibilityQuoteService(
                memberLookupClient, pipeline, costShareCalculator,
                schemeReader, accReader, auditPublisher);
    }

    @Test
    void unknownMemberNumber_errors404() {
        when(memberLookupClient.findByMemberNumber("MISSING")).thenReturn(Mono.empty());

        StepVerifier.create(service.quote(request("MISSING"), PROVIDER_ID, "u1", "u1@example.com"))
                .expectError(EligibilityQuoteService.MemberNotFoundException.class)
                .verify();
    }

    @Test
    void activeMember_returnsQuoteWithSevenBuckets() {
        when(memberLookupClient.findByMemberNumber("M-100"))
                .thenReturn(Mono.just(member("M-100", "active", null)));

        EligibilityQuoteResponse response = service
                .quote(request("M-100"), PROVIDER_ID, "u1", "u1@example.com")
                .block();

        assertThat(response).isNotNull();
        assertThat(response.coverage()).isEqualTo("ACTIVE");
        assertThat(response.estimatedAllowed()).isEqualByComparingTo("500");
        assertThat(response.estimatedCopay()).isEqualByComparingTo("25");
        assertThat(response.estimatedPatientResponsibility()).isEqualByComparingTo("125");
        assertThat(response.estimatedPlanPaid()).isEqualByComparingTo("375");   // 500 - 125
        assertThat(response.deductibleRemaining()).isEqualByComparingTo("400"); // 500 - 100
        assertThat(response.oopMaxRemaining()).isEqualByComparingTo("1875");    // 2000 - 125
        assertThat(response.currencyCode()).isEqualTo("USD");
    }

    @Test
    void suspendedWithArrearsReason_returnsInArrearsCoverage() {
        when(memberLookupClient.findByMemberNumber("M-101"))
                .thenReturn(Mono.just(member("M-101", "suspended", "CONTRIBUTION_ARREARS")));

        EligibilityQuoteResponse response = service
                .quote(request("M-101"), PROVIDER_ID, "u1", "u1@example.com")
                .block();

        assertThat(response).isNotNull();
        assertThat(response.coverage()).isEqualTo("IN_ARREARS");
    }

    @Test
    void terminatedMember_returnsTerminatedCoverage() {
        when(memberLookupClient.findByMemberNumber("M-102"))
                .thenReturn(Mono.just(member("M-102", "terminated", null)));

        EligibilityQuoteResponse response = service
                .quote(request("M-102"), PROVIDER_ID, "u1", "u1@example.com")
                .block();

        assertThat(response).isNotNull();
        assertThat(response.coverage()).isEqualTo("TERMINATED");
    }

    @Test
    void successfulQuote_emitsAuditEventWithFriendlyEntityName() {
        when(memberLookupClient.findByMemberNumber("M-100"))
                .thenReturn(Mono.just(member("M-100", "active", null)));

        service.quote(request("M-100"), PROVIDER_ID, "u1", "u1@example.com").block();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        AuditEvent event = captor.getValue();

        assertThat(event.entityType()).isEqualTo("EligibilityQuote");
        assertThat(event.action()).isEqualTo("CREATE");
        assertThat(event.actorId()).isEqualTo("u1");
        assertThat(event.actorEmail()).isEqualTo("u1@example.com");
        // Friendly text — not the entityId UUID (per feedback_audit_entity_name).
        assertThat(event.entityName())
                .contains("M-100")
                .doesNotContain(event.entityId());
        assertThat(event.newValue())
                .containsEntry("memberNumber", "M-100")
                .containsEntry("coverage", "ACTIVE")
                .containsEntry("estimatedPatientResponsibility", "125");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private EligibilityQuoteRequest request(String memberNumber) {
        return new EligibilityQuoteRequest(
                memberNumber, "CONSULTATION", List.of("CONS-01"),
                new BigDecimal("500"), "USD", LocalDate.of(2026, 6, 1));
    }

    private MemberSummary member(String number, String status, String suspendReason) {
        return new MemberSummary(
                MEMBER_ID, number, "Test", "Member", status, suspendReason,
                SCHEME_ID, null, LocalDate.of(2025, 1, 1), null);
    }

    private CostShareBreakdown breakdown(String allowed, String deductible, String copay,
                                          String coinsurance, String notCovered,
                                          String shortfall, String memberResp) {
        return new CostShareBreakdown(
                new BigDecimal(allowed), new BigDecimal(deductible), new BigDecimal(copay),
                new BigDecimal(coinsurance), new BigDecimal(notCovered),
                new BigDecimal(shortfall), new BigDecimal(memberResp));
    }
}
