package com.medfund.finance.producer.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.producer.dto.CommissionAggregateRow;
import com.medfund.finance.producer.repository.CommissionAggregateQueryRepository.AggregateDimension;
import com.medfund.finance.producer.service.CommissionAggregateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice for {@link CommissionAggregateController} — the local
 * finance-service composer feed for the Phase 18 executive KPI dashboard's
 * EXPENSE_RATIO (acquisition-ratio) numerator. Ungated by
 * {@code @RequiresReport} — service-to-service surface — but gated by
 * FINANCE_VIEW_SUBLEDGER (the @RequiresPermission aspect is not loaded in
 * the WebFlux slice, so the endpoint answers without an authorised call).
 */
@WebFluxTest(CommissionAggregateController.class)
@Import(SecurityConfig.class)
class CommissionAggregateControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private CommissionAggregateService service;

    private static final String START = "2026-07-01";
    private static final String END = "2026-08-01";

    @Test
    void commissions_defaultsToTenantDimension() {
        when(service.aggregatePaid(any(), any(), eq(AggregateDimension.TENANT), isNull(), isNull()))
                .thenReturn(Mono.just(List.of(
                        new CommissionAggregateRow(null, null, null, "USD",
                                new BigDecimal("250.00"), 3L))));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/commissions")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].currencyCode").isEqualTo("USD")
                .jsonPath("$[0].totalPaid").isEqualTo(250.00)
                .jsonPath("$[0].rowCount").isEqualTo(3);

        verify(service).aggregatePaid(any(), any(), eq(AggregateDimension.TENANT), isNull(), isNull());
    }

    @Test
    void commissions_lineDimensionAndFilter() {
        when(service.aggregatePaid(any(), any(), eq(AggregateDimension.LINE), eq("HEALTH"), isNull()))
                .thenReturn(Mono.just(List.of(
                        new CommissionAggregateRow(null, null, "HEALTH", "USD",
                                new BigDecimal("100.00"), 2L))));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/commissions")
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
    void commissions_producerDimensionPassesProducerIdFilter() {
        UUID producerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(service.aggregatePaid(any(), any(), eq(AggregateDimension.PRODUCER), isNull(), eq(producerId)))
                .thenReturn(Mono.just(List.of(
                        new CommissionAggregateRow(producerId, "Ace Brokers", null, "USD",
                                new BigDecimal("400.00"), 5L))));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/commissions")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .queryParam("dimension", "PRODUCER")
                        .queryParam("producerId", producerId.toString())
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].producerId").isEqualTo(producerId.toString())
                .jsonPath("$[0].producerName").isEqualTo("Ace Brokers");

        verify(service).aggregatePaid(any(), any(), eq(AggregateDimension.PRODUCER), isNull(), eq(producerId));
    }

    @Test
    void commissions_lineAndProducerDimension() {
        UUID producerId = UUID.fromString("22222222-3333-4444-5555-666666666666");
        when(service.aggregatePaid(any(), any(), eq(AggregateDimension.LINE_AND_PRODUCER), eq("LIFE"), isNull()))
                .thenReturn(Mono.just(List.of(
                        new CommissionAggregateRow(producerId, "Ace Brokers", "LIFE", "USD",
                                new BigDecimal("175.00"), 4L))));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/commissions")
                        .queryParam("periodStart", START)
                        .queryParam("periodEnd", END)
                        .queryParam("dimension", "LINE_AND_PRODUCER")
                        .queryParam("insuranceLine", "LIFE")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].insuranceLine").isEqualTo("LIFE")
                .jsonPath("$[0].producerName").isEqualTo("Ace Brokers");
    }

    @Test
    void commissions_missingPeriodParams_returnsBadRequest() {
        webTestClient.mutateWith(mockJwt()).get()
                .uri("/api/v1/reports/aggregate/commissions")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().is4xxClientError();
    }
}
