package com.medfund.claims.service;

import com.medfund.claims.client.MemberLookupClient;
import com.medfund.claims.client.MemberLookupClient.DependantSummary;
import com.medfund.claims.client.MemberLookupClient.MemberSummary;
import com.medfund.claims.costshare.CostShareConfig;
import com.medfund.claims.costshare.MemberCostShareAccumulatorReader;
import com.medfund.claims.costshare.SchemeCostShareReader;
import com.medfund.claims.dto.AdjudicationResult.CostShareBreakdown;
import com.medfund.claims.dto.AdjudicationResult.StageResult;
import com.medfund.claims.dto.EligibilityQuoteRequest;
import com.medfund.claims.dto.EligibilityQuoteResponse;
import com.medfund.claims.entity.Claim;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private static final UUID DEPENDANT_ID = UUID.randomUUID();
    private static final UUID OTHER_MEMBER_ID = UUID.randomUUID();
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

    @Test
    void quoteResolvesDependantAndSetsDependantIdOnTransientClaim() {
        when(memberLookupClient.findByMemberNumber("M-100"))
                .thenReturn(Mono.just(member("M-100", "active", null)));
        when(memberLookupClient.findDependantById(DEPENDANT_ID))
                .thenReturn(Mono.just(dependant(MEMBER_ID, "active")));

        service.quote(requestWithDependant("M-100", DEPENDANT_ID), PROVIDER_ID, "u1", "u1@example.com")
                .block();

        // Load-bearing: the transient claim handed to the pipeline must
        // carry the dependant so the INDIVIDUAL-scope accumulator lookup
        // keys on dependant_id, not member_id alone.
        ArgumentCaptor<Claim> claimCaptor = ArgumentCaptor.forClass(Claim.class);
        verify(pipeline).dryRun(claimCaptor.capture(), any());
        assertThat(claimCaptor.getValue().getDependantId()).isEqualTo(DEPENDANT_ID);
        assertThat(claimCaptor.getValue().getMemberId()).isEqualTo(MEMBER_ID);

        // Accumulator read must be keyed on dependantId under INDIVIDUAL scope.
        verify(accReader).findFor(eq(MEMBER_ID), eq(DEPENDANT_ID), eq(SCHEME_ID), anyInt());
    }

    @Test
    void quoteRejectsMismatchedDependantMember() {
        when(memberLookupClient.findByMemberNumber("M-100"))
                .thenReturn(Mono.just(member("M-100", "active", null)));
        // Dependant belongs to a different member — service must refuse.
        when(memberLookupClient.findDependantById(DEPENDANT_ID))
                .thenReturn(Mono.just(dependant(OTHER_MEMBER_ID, "active")));

        StepVerifier.create(service.quote(
                        requestWithDependant("M-100", DEPENDANT_ID), PROVIDER_ID, "u1", "u1@example.com"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void quoteFailsWhenDependantIdIsUnknown() {
        when(memberLookupClient.findByMemberNumber("M-100"))
                .thenReturn(Mono.just(member("M-100", "active", null)));
        when(memberLookupClient.findDependantById(DEPENDANT_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.quote(
                        requestWithDependant("M-100", DEPENDANT_ID), PROVIDER_ID, "u1", "u1@example.com"))
                .expectError(EligibilityQuoteService.DependantNotFoundException.class)
                .verify();
    }

    @Test
    void terminatedDependantOnActiveMember_returnsTerminatedCoverage() {
        when(memberLookupClient.findByMemberNumber("M-100"))
                .thenReturn(Mono.just(member("M-100", "active", null)));
        when(memberLookupClient.findDependantById(DEPENDANT_ID))
                .thenReturn(Mono.just(dependant(MEMBER_ID, "deactivated")));

        EligibilityQuoteResponse response = service
                .quote(requestWithDependant("M-100", DEPENDANT_ID), PROVIDER_ID, "u1", "u1@example.com")
                .block();

        assertThat(response).isNotNull();
        // A dependant whose status is deactivated / removed / swapped /
        // deceased has to override the sponsor's ACTIVE status — otherwise
        // the quote would say ACTIVE for a dependant with no coverage.
        assertThat(response.coverage()).isEqualTo("TERMINATED");
    }

    @Test
    void dependantQuote_emitsAuditEventWithDependantFields() {
        when(memberLookupClient.findByMemberNumber("M-100"))
                .thenReturn(Mono.just(member("M-100", "active", null)));
        when(memberLookupClient.findDependantById(DEPENDANT_ID))
                .thenReturn(Mono.just(dependant(MEMBER_ID, "active")));

        service.quote(requestWithDependant("M-100", DEPENDANT_ID), PROVIDER_ID, "u1", "u1@example.com")
                .block();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        AuditEvent event = captor.getValue();

        assertThat(event.newValue())
                .containsEntry("dependantId", DEPENDANT_ID.toString())
                .containsEntry("dependantMemberNumber", "D-100")
                .containsEntry("dependantName", "Junior Member");
        // Friendly entity name mentions both the sponsor and dependant
        // (per feedback_audit_entity_name — never the UUID).
        assertThat(event.entityName())
                .contains("M-100")
                .contains("Junior Member")
                .doesNotContain(event.entityId());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private EligibilityQuoteRequest request(String memberNumber) {
        return new EligibilityQuoteRequest(
                memberNumber, null, "CONSULTATION", List.of("CONS-01"),
                new BigDecimal("500"), "USD", LocalDate.of(2026, 6, 1));
    }

    private EligibilityQuoteRequest requestWithDependant(String memberNumber, UUID dependantId) {
        return new EligibilityQuoteRequest(
                memberNumber, dependantId, "CONSULTATION", List.of("CONS-01"),
                new BigDecimal("500"), "USD", LocalDate.of(2026, 6, 1));
    }

    private MemberSummary member(String number, String status, String suspendReason) {
        return new MemberSummary(
                MEMBER_ID, number, "Test", "Member", status, suspendReason,
                SCHEME_ID, null, LocalDate.of(2025, 1, 1), null);
    }

    private DependantSummary dependant(UUID sponsorId, String status) {
        return new DependantSummary(
                DEPENDANT_ID, sponsorId, "D-100", "Junior", "Member", status,
                LocalDate.of(2015, 1, 1));
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
