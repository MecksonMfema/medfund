package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.dto.ReceiptsAggregateRow;
import com.medfund.contributions.service.ReceiptsReportService;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice for {@link ReceiptsAggregateController} — the ungated
 * cross-service surface. Same shape as
 * {@link BillingAggregateController} — no SecurityEventPublisher, no
 * @RequiresReport gate; consumers call it as a peer service.
 */
@WebFluxTest(ReceiptsAggregateController.class)
@Import(SecurityConfig.class)
class ReceiptsAggregateControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private ReceiptsReportService receiptsReportService;
    @MockBean private ReportEnvelopeBuilder envelopeBuilder;

    @BeforeEach
    void stubReturns() {
        when(receiptsReportService.aggregatePerScheme(any(), any())).thenReturn(Mono.just(List.of()));
        when(receiptsReportService.perSchemePerCurrencyTotals(any(), any())).thenReturn(Mono.just(Map.of()));
        when(receiptsReportService.aggregateMonthly(any(), any(), any())).thenReturn(Mono.just(List.of()));
    }

    @Test
    void aggregate_returnsEnvelopeFromBuilder() {
        when(envelopeBuilder.build(eq(ReportKey.RECEIPTS_REPORT), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(new ReportResponse<List<ReceiptsAggregateRow>>(
                        ReportKey.RECEIPTS_REPORT.name(),
                        new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                                ReportPeriod.PeriodGrain.MONTHLY),
                        "USD",
                        List.of(new ReceiptsAggregateRow("SCHEME", UUID.randomUUID(),
                                "Gold", "USD", new BigDecimal("100.00"))),
                        Map.of(), Map.of(), List.of(), OffsetDateTime.now())));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/receipts")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(1);
    }

    @Test
    void aggregateMonthly_returnsEnvelopeForRequestedDimension() {
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.RECEIPTS_REPORT),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(new ReportResponse<List<MonthlyAggregateRow>>(
                        ReportKey.RECEIPTS_REPORT.name(),
                        new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                                ReportPeriod.PeriodGrain.MONTHLY),
                        "USD",
                        List.of(new MonthlyAggregateRow("GROUP", UUID.randomUUID(),
                                "Acme", "USD", LocalDate.of(2026, 7, 1),
                                new BigDecimal("500.00"))),
                        Map.of(), Map.of(), List.of(), OffsetDateTime.now())));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/receipts/monthly")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .queryParam("dimension", "GROUP")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].dimension").isEqualTo("GROUP");
    }
}
