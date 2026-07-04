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

@ExtendWith(MockitoExtension.class)
class SchemeChangedConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock ContributionRepository contributionRepository;
    @Mock SchemeRepository schemeRepository;
    @Mock BillingService billingService;
    @Mock LateAdjustmentService lateAdjustmentService;
    @Mock DatabaseClient databaseClient;

    private SchemeChangedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SchemeChangedConsumer(null, objectMapper,
                contributionRepository, schemeRepository, billingService,
                lateAdjustmentService, databaseClient);
        stubLookupMemberGroupId();
        lenient().when(lateAdjustmentService.postFixedAggregate(
                any(), any(), any(), any(), anyInt(), any(BigDecimal.class),
                anyString(), anyString(), anyString())).thenReturn(Mono.empty());
    }

    @Test
    void processEvent_upgradeInBilledMonth_postsUpgradeArrears() {
        // From=50, To=80 → +30 delta × 1 billed month = 30 arrears.
        // Sign check pins UPGRADE routing; a swap of the subtraction
        // order would silently flip type to REBATE and this test would
        // catch it.
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1);
        Scheme newScheme = scheme(toScheme, "HEALTH", "USD");
        when(schemeRepository.findById(toScheme)).thenReturn(Mono.just(newScheme));
        when(contributionRepository.countByPeriodAndLine(eq(effective),
                eq(effective.withDayOfMonth(effective.lengthOfMonth())), eq("HEALTH")))
                .thenReturn(Mono.just(1L));
        when(billingService.priceOneMember(eq(memberId), eq(toScheme), any(), any()))
                .thenReturn(Mono.just(new BigDecimal("80.00")));
        when(billingService.priceOneMember(eq(memberId), eq(fromScheme), any(), any()))
                .thenReturn(Mono.just(new BigDecimal("50.00")));

        String scId = UUID.randomUUID().toString();
        StepVerifier.create(consumer.processEvent(payload(scId, memberId, fromScheme, toScheme, effective)))
                .verifyComplete();

        verify(lateAdjustmentService).postFixedAggregate(
                eq(memberId), any(), eq(toScheme),
                eq(effective), eq(1),
                eq(new BigDecimal("30.00")),
                eq("USD"), eq("SCHEME_UPGRADE_ARREARS"), eq(scId));
    }

    @Test
    void processEvent_downgradeInBilledMonth_postsDowngradeRebate() {
        // From=80, To=50 → −30 delta. Type must flip to DOWNGRADE_REBATE
        // and the aggregate must be the absolute value (positive), because
        // the transaction TYPE carries the direction, not the amount.
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1);
        Scheme newScheme = scheme(toScheme, "HEALTH", "USD");
        when(schemeRepository.findById(toScheme)).thenReturn(Mono.just(newScheme));
        when(contributionRepository.countByPeriodAndLine(any(), any(), eq("HEALTH")))
                .thenReturn(Mono.just(1L));
        when(billingService.priceOneMember(eq(memberId), eq(toScheme), any(), any()))
                .thenReturn(Mono.just(new BigDecimal("50.00")));
        when(billingService.priceOneMember(eq(memberId), eq(fromScheme), any(), any()))
                .thenReturn(Mono.just(new BigDecimal("80.00")));

        String scId = UUID.randomUUID().toString();
        StepVerifier.create(consumer.processEvent(payload(scId, memberId, fromScheme, toScheme, effective)))
                .verifyComplete();

        verify(lateAdjustmentService).postFixedAggregate(
                eq(memberId), any(), eq(toScheme),
                eq(effective), eq(1),
                eq(new BigDecimal("30.00")),
                eq("USD"), eq("SCHEME_DOWNGRADE_REBATE"), eq(scId));
    }

    @Test
    void processEvent_zeroDelta_isNoOp() {
        // Equal pricing across schemes → nothing to post. Guards against
        // the pre-refactor bug where a "0.00" aggregate would still emit
        // a transaction row with no effect on the ledger but a confusing
        // row in the audit trail.
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1);
        Scheme newScheme = scheme(toScheme, "HEALTH", "USD");
        when(schemeRepository.findById(toScheme)).thenReturn(Mono.just(newScheme));
        when(contributionRepository.countByPeriodAndLine(any(), any(), eq("HEALTH")))
                .thenReturn(Mono.just(1L));
        when(billingService.priceOneMember(eq(memberId), any(), any(), any()))
                .thenReturn(Mono.just(new BigDecimal("60.00")));

        String scId = UUID.randomUUID().toString();
        StepVerifier.create(consumer.processEvent(payload(scId, memberId, fromScheme, toScheme, effective)))
                .verifyComplete();

        verify(lateAdjustmentService, never()).postFixedAggregate(
                any(), any(), any(), any(), anyInt(), any(BigDecimal.class),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_futureEffectiveDate_isNoOp() {
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate future = LocalDate.now().plusMonths(2).withDayOfMonth(1);

        StepVerifier.create(consumer.processEvent(payload(
                        UUID.randomUUID().toString(), memberId, fromScheme, toScheme, future)))
                .verifyComplete();

        verify(schemeRepository, never()).findById(any(UUID.class));
        verify(lateAdjustmentService, never()).postFixedAggregate(
                any(), any(), any(), any(), anyInt(), any(BigDecimal.class),
                anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_missingRequiredFields_isNoOp() {
        // Missing fromSchemeId → skip. Same guard as the enrolment
        // consumer — never fabricate defaults on money-moving events.
        String json = String.format(
                "{\"schemeChangeId\":\"%s\",\"memberId\":\"%s\",\"toSchemeId\":\"%s\","
                + "\"effectiveDate\":\"2026-07-01\"}",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(schemeRepository, never()).findById(any(UUID.class));
    }

    @Test
    void processEvent_invalidJson_returnsError() {
        StepVerifier.create(consumer.processEvent("not valid {{{")).verifyError();
    }

    /** SchemeChangedConsumer's postDelta calls db.sql("SELECT group_id ...")
     *  to resolve the member's current group. Stub as returning empty (no
     *  group) so the tests focus on the delta / type routing. */
    @SuppressWarnings("unchecked")
    private void stubLookupMemberGroupId() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<java.util.Optional<UUID>> fetch =
                mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.one()).thenReturn(Mono.just(java.util.Optional.<UUID>empty()));
    }

    private static Scheme scheme(UUID id, String line, String currency) {
        Scheme s = new Scheme();
        s.setId(id);
        s.setInsuranceLine(line);
        s.setCurrencyCode(currency);
        return s;
    }

    private static String payload(String scId, UUID memberId, UUID fromScheme,
                                    UUID toScheme, LocalDate effective) {
        return String.format(
                "{\"schemeChangeId\":\"%s\",\"memberId\":\"%s\","
                + "\"fromSchemeId\":\"%s\",\"toSchemeId\":\"%s\","
                + "\"effectiveDate\":\"%s\"}",
                scId, memberId, fromScheme, toScheme, effective);
    }
}
