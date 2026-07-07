package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.contributions.service.BillingService;
import com.medfund.contributions.service.LateAdjustmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the ledger-transfer behaviour on a back-dated group change:
 *
 * <ul>
 *   <li>REBATE on old group + ARREARS on new group, same amount, opposite signs.
 *   <li>Forward-dated → no-op (regular billing cycle handles routing).
 *   <li>Non-GROUP_CHANGE envelopes are ignored (shared topic).
 *   <li>Missing old/new/effective → no-op (never fabricate a transfer).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GroupChangedConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock ContributionRepository contributionRepository;
    @Mock SchemeRepository schemeRepository;
    @Mock BillingService billingService;
    @Mock LateAdjustmentService lateAdjustmentService;
    @Mock DatabaseClient databaseClient;

    private GroupChangedConsumer consumer;
    private UUID schemeId;

    @BeforeEach
    void setUp() {
        consumer = new GroupChangedConsumer(null, objectMapper,
                contributionRepository, schemeRepository, billingService,
                lateAdjustmentService, databaseClient);
        schemeId = UUID.randomUUID();
        stubLookupMemberSchemeId(schemeId);
        lenient().when(lateAdjustmentService.postFixedAggregate(
                any(), any(), any(), any(), anyInt(), any(BigDecimal.class),
                anyString(), anyString(), anyString())).thenReturn(Mono.empty());
    }

    // ------------------------------------------------------------------
    // Happy path — back-dated → REBATE(old) + ARREARS(new)
    // ------------------------------------------------------------------

    @Test
    void processEvent_backdatedOneMonth_postsRebateOnOldAndArrearsOnNew() {
        UUID memberId = UUID.randomUUID();
        UUID fromGroup = UUID.randomUUID();
        UUID toGroup = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1);
        Scheme scheme = scheme(schemeId, "HEALTH", "USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(contributionRepository.countByPeriodAndLine(
                eq(effective), eq(effective.withDayOfMonth(effective.lengthOfMonth())), eq("HEALTH")))
                .thenReturn(Mono.just(1L));
        when(billingService.priceOneMember(eq(memberId), eq(schemeId), any(), any()))
                .thenReturn(Mono.just(new BigDecimal("120.00")));

        StepVerifier.create(consumer.processEvent(backdated(memberId, fromGroup, toGroup, effective)))
                .verifyComplete();

        // REBATE on old group
        verify(lateAdjustmentService).postFixedAggregate(
                eq(memberId), eq(fromGroup), eq(schemeId), eq(effective), eq(1),
                eq(new BigDecimal("120.00")),
                eq("USD"), eq("GROUP_CHANGE_REBATE"), anyString());
        // ARREARS on new group
        verify(lateAdjustmentService).postFixedAggregate(
                eq(memberId), eq(toGroup), eq(schemeId), eq(effective), eq(1),
                eq(new BigDecimal("120.00")),
                eq("USD"), eq("GROUP_CHANGE_ARREARS"), anyString());
    }

    // ------------------------------------------------------------------
    // Forward-dated → no ledger post
    // ------------------------------------------------------------------

    @Test
    void processEvent_forwardDated_isNoOp() {
        UUID memberId = UUID.randomUUID();
        UUID fromGroup = UUID.randomUUID();
        UUID toGroup = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1).plusMonths(1);

        StepVerifier.create(consumer.processEvent(payloadWithBackdatedFlag(
                        memberId, fromGroup, toGroup, effective, false)))
                .verifyComplete();

        verify(schemeRepository, never()).findById(any(UUID.class));
        verify(lateAdjustmentService, never()).postFixedAggregate(
                any(), any(), any(), any(), anyInt(), any(BigDecimal.class),
                anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Non-GROUP_CHANGE envelope on the shared topic → ignored
    // ------------------------------------------------------------------

    @Test
    void processEvent_nonGroupChangeKind_isIgnored() {
        // SWAP_APPLIED shares the topic. Consumer must silently skip.
        String json = String.format(
                "{\"event\":\"MEMBER_CHANGED\",\"memberId\":\"%s\",\"changeKind\":\"SWAP_APPLIED\","
                + "\"oldValue\":\"%s\",\"newValue\":\"%s\",\"effectiveDate\":\"2026-07-01\","
                + "\"backdated\":\"true\"}",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(schemeRepository, never()).findById(any(UUID.class));
    }

    // ------------------------------------------------------------------
    // Missing fields → no-op guard
    // ------------------------------------------------------------------

    @Test
    void processEvent_missingOldValue_isNoOp() {
        String json = String.format(
                "{\"event\":\"MEMBER_CHANGED\",\"memberId\":\"%s\",\"changeKind\":\"GROUP_CHANGE\","
                + "\"newValue\":\"%s\",\"effectiveDate\":\"2026-07-01\",\"backdated\":\"true\"}",
                UUID.randomUUID(), UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(schemeRepository, never()).findById(any(UUID.class));
    }

    @Test
    void processEvent_invalidJson_returnsError() {
        StepVerifier.create(consumer.processEvent("not valid {{{")).verifyError();
    }

    // ------------------------------------------------------------------
    // Zero already-billed months → no transfer
    // ------------------------------------------------------------------

    @Test
    void processEvent_noAlreadyBilledMonths_isNoOp() {
        UUID memberId = UUID.randomUUID();
        UUID fromGroup = UUID.randomUUID();
        UUID toGroup = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1);
        Scheme scheme = scheme(schemeId, "HEALTH", "USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        // No prior billing runs — walk finds zero committed months.
        when(contributionRepository.countByPeriodAndLine(any(), any(), eq("HEALTH")))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(consumer.processEvent(backdated(memberId, fromGroup, toGroup, effective)))
                .verifyComplete();

        verify(lateAdjustmentService, never()).postFixedAggregate(
                any(), any(), any(), any(), anyInt(), any(BigDecimal.class),
                anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubLookupMemberSchemeId(UUID scheme) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<Optional<UUID>> fetch =
                mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.one()).thenReturn(Mono.just(Optional.of(scheme)));
    }

    private static Scheme scheme(UUID id, String line, String currency) {
        Scheme s = new Scheme();
        s.setId(id);
        s.setInsuranceLine(line);
        s.setCurrencyCode(currency);
        return s;
    }

    private static String backdated(UUID memberId, UUID from, UUID to, LocalDate effective) {
        return payloadWithBackdatedFlag(memberId, from, to, effective, true);
    }

    private static String payloadWithBackdatedFlag(UUID memberId, UUID from, UUID to,
                                                     LocalDate effective, boolean backdated) {
        return String.format(
                "{\"event\":\"MEMBER_CHANGED\",\"memberId\":\"%s\",\"changeKind\":\"GROUP_CHANGE\","
                + "\"oldValue\":\"%s\",\"newValue\":\"%s\",\"effectiveDate\":\"%s\","
                + "\"backdated\":\"%s\"}",
                memberId, from, to, effective, backdated);
    }
}
