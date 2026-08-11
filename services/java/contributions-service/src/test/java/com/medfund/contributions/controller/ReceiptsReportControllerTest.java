package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.ReceiptsSummaryRow;
import com.medfund.contributions.service.ReceiptsExcelService;
import com.medfund.contributions.service.ReceiptsReportService;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.security.SecurityEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice test for {@link ReceiptsReportController}. Same shape as
 * {@code BillingReportControllerTest} — pins route wiring, envelope
 * delegation, XLSX response envelope, and SecurityEvent emission for the
 * receipts family's three summary surfaces and their exports. Detail
 * endpoints get one representative check per dimension.
 *
 * <p>Report-toggle gating isn't exercised here — the aspect isn't loaded
 * in the WebFlux slice. Aspect-level 403 path is pinned by
 * {@code ReportGuardAspectTest} in shared; the family ITs cover the
 * end-to-end round-trip.
 */
@WebFluxTest(ReceiptsReportController.class)
@Import(SecurityConfig.class)
class ReceiptsReportControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private ReceiptsReportService receiptsReportService;
    @MockBean private ReceiptsExcelService receiptsExcelService;
    @MockBean private ReportEnvelopeBuilder envelopeBuilder;
    @MockBean private SecurityEventPublisher securityEventPublisher;

    @BeforeEach
    void stubMockBeanReturns() {
        when(securityEventPublisher.publishDataAccess(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        // Mockito 5's any(Mono.class) rejects nulls; stub the service returns
        // so the envelope-builder mock matches. See BillingReportControllerTest.
        when(receiptsReportService.perSchemeSummary(any(), any())).thenReturn(Mono.just(List.of()));
        when(receiptsReportService.perSchemePerCurrencyTotals(any(), any())).thenReturn(Mono.just(Map.of()));
        when(receiptsReportService.perGroupSummary(any(), any())).thenReturn(Mono.just(List.of()));
        when(receiptsReportService.perGroupPerCurrencyTotals(any(), any())).thenReturn(Mono.just(Map.of()));
        when(receiptsReportService.perMemberSummary(any(), any(), any(), any(), any(), any(int.class), any(int.class)))
                .thenReturn(Mono.just(PageResponse.of(List.of(), 0L, 0, 50)));
        when(receiptsReportService.perMemberPerCurrencyTotals(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(Map.of()));
        when(receiptsReportService.detail(any(), any(), any(), any(), any(), any(), any(int.class), any(int.class)))
                .thenReturn(Mono.empty());
        when(receiptsReportService.unallocatedDetail(any(), any(), any(), any(), any(int.class), any(int.class)))
                .thenReturn(Mono.empty());
    }

    // ── /schemes ─────────────────────────────────────────────────────────

    @Test
    void schemesReport_returnsEnvelopeFromBuilder() {
        ReportResponse<List<ReceiptsSummaryRow>> envelope = envelope(
                ReportKey.RECEIPTS_REPORT, List.of(summaryRow("Gold", "USD", 100)));
        when(envelopeBuilder.build(eq(ReportKey.RECEIPTS_REPORT), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/receipts/schemes")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .queryParam("reportingCurrency", "USD")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("RECEIPTS_REPORT")
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.length()").isEqualTo(1);
    }

    @Test
    void schemesReport_missingPeriodParams_returnsBadRequest() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reports/receipts/schemes")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void exportSchemesExcel_emitsSecurityEventAndReturnsAttachment() {
        byte[] payload = new byte[]{1, 2, 3};
        when(receiptsExcelService.schemesReportExcel(any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/receipts/schemes/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"receipts-schemes-2026-07-01-to-2026-07-31\\.xlsx\"")
                .expectBody(byte[].class).isEqualTo(payload);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                keyCap.capture(), any());
        assertThat(keyCap.getValue()).isEqualTo(ReportKey.RECEIPTS_REPORT.name());
    }

    // ── /groups ──────────────────────────────────────────────────────────

    @Test
    void groupsReport_returnsEnvelope() {
        when(envelopeBuilder.build(eq(ReportKey.RECEIPTS_REPORT), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope(ReportKey.RECEIPTS_REPORT, List.of())));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/receipts/groups")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo("RECEIPTS_REPORT");
    }

    @Test
    void exportGroupsExcel_emitsSecurityEventWithReceiptsReportKey() {
        byte[] payload = new byte[]{4, 5, 6};
        when(receiptsExcelService.groupsReportExcel(any(), any(), any())).thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/receipts/groups/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(payload);

        verify(securityEventPublisher).publishDataAccess(any(), any(), any(),
                eq(ReportKey.RECEIPTS_REPORT.name()), any());
    }

    // ── /members ─────────────────────────────────────────────────────────

    @Test
    void membersReport_delegatesToEnvelopeBuilder() {
        ReportResponse<PageResponse<ReceiptsSummaryRow>> envelope = new ReportResponse<>(
                ReportKey.RECEIPTS_REPORT.name(),
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                        ReportPeriod.PeriodGrain.MONTHLY),
                "USD",
                PageResponse.of(List.of(summaryRow("M-001 — Alice", "USD", 50)), 1L, 0, 50),
                Map.of(), Map.of(), List.of(), OffsetDateTime.now());
        when(envelopeBuilder.build(eq(ReportKey.RECEIPTS_REPORT), any(ReportPeriod.class),
                any(), any(Mono.class), any(Mono.class)))
                .thenReturn(Mono.just(envelope));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/receipts/members")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .queryParam("search", "Alice")
                        .queryParam("insuranceLine", "LIFE")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("RECEIPTS_REPORT")
                .jsonPath("$.data.total").isEqualTo(1);
    }

    // ── Detail ───────────────────────────────────────────────────────────

    @Test
    void memberDetail_usesBuildNoAggregateWithReceiptsAggregateKey() {
        UUID memberId = UUID.randomUUID();
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.RECEIPTS_AGGREGATE),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(new ReportResponse<>(
                        ReportKey.RECEIPTS_AGGREGATE.name(),
                        new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                                ReportPeriod.PeriodGrain.MONTHLY),
                        "USD", null, Map.of(), Map.of(), List.of(), OffsetDateTime.now())));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/receipts/members/" + memberId)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reportKey").isEqualTo("RECEIPTS_AGGREGATE");
    }

    @Test
    void schemeDetail_withUnallocatedFlag_routesToUnallocatedPath() {
        when(envelopeBuilder.buildNoAggregate(eq(ReportKey.RECEIPTS_AGGREGATE),
                any(ReportPeriod.class), any(), any(Mono.class)))
                .thenReturn(Mono.just(new ReportResponse<>(
                        ReportKey.RECEIPTS_AGGREGATE.name(),
                        new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                                ReportPeriod.PeriodGrain.MONTHLY),
                        "USD", null, Map.of(), Map.of(), List.of(), OffsetDateTime.now())));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/reports/receipts/schemes/" + UUID.randomUUID())
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .queryParam("unallocated", "true")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(receiptsReportService).unallocatedDetail(any(), any(), any(), any(),
                any(int.class), any(int.class));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static ReceiptsSummaryRow summaryRow(String name, String ccy, int amount) {
        return new ReceiptsSummaryRow(
                UUID.randomUUID(), name, null, ccy,
                new BigDecimal(amount), 1L);
    }

    private static <T> ReportResponse<T> envelope(ReportKey key, T data) {
        return new ReportResponse<>(
                key.name(),
                new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                        ReportPeriod.PeriodGrain.MONTHLY),
                "USD", data, Map.of(), Map.of(), List.of(), OffsetDateTime.now());
    }
}
