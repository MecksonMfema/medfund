package com.medfund.contributions.service;

import com.medfund.contributions.dto.BillingAggregateRow;
import com.medfund.contributions.dto.BillingMonthlyBucket;
import com.medfund.contributions.dto.GroupBillingDetailResponse;
import com.medfund.contributions.dto.GroupBillingSummaryRow;
import com.medfund.contributions.dto.SchemeBillingDetailResponse;
import com.medfund.contributions.dto.SchemeBillingSummaryRow;
import com.medfund.contributions.repository.BillingReportQueryRepository;
import com.medfund.shared.report.PerCurrencyTotal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BillingReportService} — verifies the service
 * composes the two queries (summary + monthly) into a detail response
 * without in-memory aggregation, and defaults the scheme name /
 * insurance line safely when the summary is empty.
 */
@ExtendWith(MockitoExtension.class)
class BillingReportServiceTest {

    @Mock private BillingReportQueryRepository repository;
    @InjectMocks private BillingReportService service;

    private final LocalDate periodStart = LocalDate.of(2026, 7, 1);
    private final LocalDate periodEnd   = LocalDate.of(2026, 7, 31);

    @Test
    void perSchemeSummary_delegatesToRepository() {
        SchemeBillingSummaryRow row = new SchemeBillingSummaryRow(
                UUID.randomUUID(), "Gold", "HEALTH", "USD",
                10L, 5L, 15L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("50.00"));
        when(repository.perSchemeSummary(periodStart, periodEnd))
                .thenReturn(Flux.just(row));

        StepVerifier.create(service.perSchemeSummary(periodStart, periodEnd))
                .expectNext(List.of(row))
                .verifyComplete();
    }

    @Test
    void perSchemePerCurrencyTotals_delegatesToRepository() {
        Map<String, PerCurrencyTotal> totals = Map.of("USD",
                new PerCurrencyTotal(new BigDecimal("100.00"), 3L));
        when(repository.perSchemePerCurrencyTotals(periodStart, periodEnd))
                .thenReturn(Mono.just(totals));

        StepVerifier.create(service.perSchemePerCurrencyTotals(periodStart, periodEnd))
                .expectNext(totals)
                .verifyComplete();
    }

    @Test
    void schemeDetail_stitchesSummaryAndMonthlyIntoDetailResponse() {
        UUID schemeId = UUID.randomUUID();
        SchemeBillingSummaryRow summary = new SchemeBillingSummaryRow(
                schemeId, "Gold", "HEALTH", "USD",
                10L, 5L, 15L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("50.00"));
        BillingMonthlyBucket bucket = new BillingMonthlyBucket(
                periodStart, "USD", 10L, 5L,
                new BigDecimal("100.00"), new BigDecimal("50.00"));
        when(repository.schemeSummary(schemeId, periodStart, periodEnd))
                .thenReturn(Flux.just(summary));
        when(repository.schemeMonthly(schemeId, periodStart, periodEnd))
                .thenReturn(Flux.just(bucket));

        StepVerifier.create(service.schemeDetail(schemeId, periodStart, periodEnd))
                .assertNext(detail -> {
                    assertThat(detail.schemeId()).isEqualTo(schemeId);
                    assertThat(detail.schemeName()).isEqualTo("Gold");
                    assertThat(detail.insuranceLine()).isEqualTo("HEALTH");
                    assertThat(detail.perCurrencySummary()).containsExactly(summary);
                    assertThat(detail.monthlyBreakdown()).containsExactly(bucket);
                })
                .verifyComplete();
    }

    @Test
    void schemeDetail_emptySummary_defaultsSchemeNameAndLine() {
        // Reporting on a scheme that had no committed rows in the window
        // must not NPE — client sees the scheme's id echoed back plus
        // safe defaults so the "no data" state renders cleanly.
        UUID schemeId = UUID.randomUUID();
        when(repository.schemeSummary(schemeId, periodStart, periodEnd)).thenReturn(Flux.empty());
        when(repository.schemeMonthly(schemeId, periodStart, periodEnd)).thenReturn(Flux.empty());

        StepVerifier.create(service.schemeDetail(schemeId, periodStart, periodEnd))
                .assertNext(detail -> {
                    assertThat(detail.schemeId()).isEqualTo(schemeId);
                    assertThat(detail.schemeName()).isEmpty();
                    assertThat(detail.insuranceLine()).isEqualTo("HEALTH");
                    assertThat(detail.perCurrencySummary()).isEmpty();
                    assertThat(detail.monthlyBreakdown()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void groupDetail_stitchesSummaryAndMonthlyIntoDetailResponse() {
        UUID groupId = UUID.randomUUID();
        GroupBillingSummaryRow summary = new GroupBillingSummaryRow(
                groupId, "Acme Corp", "USD",
                20L, 10L, 30L,
                new BigDecimal("500.00"), new BigDecimal("250.00"));
        BillingMonthlyBucket bucket = new BillingMonthlyBucket(
                periodStart, "USD", 20L, 10L,
                new BigDecimal("500.00"), new BigDecimal("250.00"));
        when(repository.groupSummary(groupId, periodStart, periodEnd))
                .thenReturn(Flux.just(summary));
        when(repository.groupMonthly(groupId, periodStart, periodEnd))
                .thenReturn(Flux.just(bucket));

        StepVerifier.create(service.groupDetail(groupId, periodStart, periodEnd))
                .assertNext(detail -> {
                    assertThat(detail.groupId()).isEqualTo(groupId);
                    assertThat(detail.groupName()).isEqualTo("Acme Corp");
                    assertThat(detail.perCurrencySummary()).containsExactly(summary);
                    assertThat(detail.monthlyBreakdown()).containsExactly(bucket);
                })
                .verifyComplete();
    }

    @Test
    void aggregatePerScheme_delegatesToRepository() {
        BillingAggregateRow row = new BillingAggregateRow(
                UUID.randomUUID(), "Gold", "USD", new BigDecimal("100.00"));
        when(repository.aggregatePerScheme(periodStart, periodEnd)).thenReturn(Flux.just(row));

        StepVerifier.create(service.aggregatePerScheme(periodStart, periodEnd))
                .expectNext(List.of(row))
                .verifyComplete();
    }
}
