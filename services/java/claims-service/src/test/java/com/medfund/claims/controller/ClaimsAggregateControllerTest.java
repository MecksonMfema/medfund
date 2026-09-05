package com.medfund.claims.controller;

import com.medfund.claims.config.SecurityConfig;
import com.medfund.claims.dto.ClaimsIncurredAggregateRow;
import com.medfund.claims.repository.ClaimsAggregateQueryRepository.Dimension;
import com.medfund.claims.service.ClaimsAggregateService;
import com.medfund.claims.service.ClaimsReportService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice test for {@link ClaimsAggregateController} (Phase 4 §A,
 * G44). Pins the cross-service aggregate routes — ungated by
 * {@code RequiresReport} by design, still permission-gated — and their
 * envelope delegation.
 */
@WebFluxTest(ClaimsAggregateController.class)
@Import(SecurityConfig.class)
class ClaimsAggregateControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private ClaimsReportService claimsReportService;
    @MockBean private ReportEnvelopeBuilder envelopeBuilder;
    @MockBean private ClaimsAggregateService claimsAggregateService;

    private static final String START = "2026-07-01";
    private static final String END = "2026-07-31";

    @BeforeEach
    void stubMockBeanReturns() {
        when(claimsReportService.aggregate(any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(claimsReportService.aggregateMonthly(any(), any(), any())).thenReturn(Mono.just(List.of()));
        when(claimsReportService.claimsPerCurrencyTotals(any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
    }

    @Test
    void aggregate_delegatesToEnvelopeBuilderWithDefaultSchemeDimension() {
        when(envelopeBuilder.build(eq(ReportKey.CLAIMS_SUMMARY), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope()));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/aggregate/claims")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo(ReportKey.CLAIMS_SUMMARY.name());

        verify(claimsReportService).aggregate(eq("SCHEME"), any(), any());
    }

    @Test
    void aggregate_missingPeriodParams_returnsBadRequest() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reports/aggregate/claims")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void aggregateMonthly_delegatesWithRequestedDimension() {
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.CLAIMS_SUMMARY),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(envelope()));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/aggregate/claims/monthly")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .queryParam("dimension", "PROVIDER")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo(ReportKey.CLAIMS_SUMMARY.name());

        verify(claimsReportService).aggregateMonthly(eq("PROVIDER"), any(), any());
    }

    private static ReportResponse<List<Object>> envelope() {
        return new ReportResponse<>(
                ReportKey.CLAIMS_SUMMARY.name(),
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                        ReportPeriod.PeriodGrain.MONTHLY),
                "USD", List.of(), Map.of(), Map.of(), List.of(), OffsetDateTime.now());
    }

    // ── Phase 18 K9 /claims-incurred ────────────────────────────────

    @Test
    void claimsIncurred_defaultsToTenantDimension() {
        when(claimsAggregateService.claimsIncurred(any(), any(), eq(Dimension.TENANT), isNull(), isNull()))
                .thenReturn(Mono.just(List.of(
                        new ClaimsIncurredAggregateRow(null, null, null, "USD",
                                new BigDecimal("500.00"),
                                new BigDecimal("200.00"), new BigDecimal("300.00"),
                                new BigDecimal("100.00"), new BigDecimal("600.00"), 4L))));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/aggregate/claims-incurred")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].currencyCode").isEqualTo("USD")
                .jsonPath("$[0].totalPaid").isEqualTo(500.00)
                .jsonPath("$[0].reserveMovement").isEqualTo(100.00)
                .jsonPath("$[0].subtotalIncurredExIbnr").isEqualTo(600.00)
                .jsonPath("$[0].claimCount").isEqualTo(4);

        verify(claimsAggregateService).claimsIncurred(any(), any(), eq(Dimension.TENANT), isNull(), isNull());
    }

    @Test
    void claimsIncurred_lineDimensionAndFilter() {
        when(claimsAggregateService.claimsIncurred(any(), any(), eq(Dimension.LINE), eq("HEALTH"), isNull()))
                .thenReturn(Mono.just(List.of(
                        new ClaimsIncurredAggregateRow(null, null, "HEALTH", "USD",
                                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, new BigDecimal("100.00"), 2L))));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/aggregate/claims-incurred")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .queryParam("dimension", "LINE")
                        .queryParam("insuranceLine", "HEALTH")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].insuranceLine").isEqualTo("HEALTH");
    }

    @Test
    void claimsIncurred_schemeDimensionPassesSchemeIdFilter() {
        UUID schemeId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        when(claimsAggregateService.claimsIncurred(any(), any(), eq(Dimension.SCHEME), isNull(), eq(schemeId)))
                .thenReturn(Mono.just(List.of()));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/aggregate/claims-incurred")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .queryParam("dimension", "SCHEME")
                        .queryParam("schemeId", schemeId.toString())
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);

        verify(claimsAggregateService).claimsIncurred(any(), any(), eq(Dimension.SCHEME), isNull(), eq(schemeId));
    }

    @Test
    void claimsIncurred_missingPeriodParams_returnsBadRequest() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reports/aggregate/claims-incurred")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().is4xxClientError();
    }
}
