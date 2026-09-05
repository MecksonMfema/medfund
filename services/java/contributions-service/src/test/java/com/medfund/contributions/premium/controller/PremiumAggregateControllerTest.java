package com.medfund.contributions.premium.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.premium.dto.PremiumEarnedAggregateRow;
import com.medfund.contributions.premium.repository.PremiumAggregateQueryRepository.Dimension;
import com.medfund.contributions.premium.service.PremiumAggregateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice for {@link PremiumAggregateController} — the ungated
 * cross-service surface consumed by the Phase 18 KPI composer. Same shape
 * as {@link com.medfund.contributions.controller.BillingAggregateController}
 * — no SecurityEventPublisher, no {@code @RequiresReport} gate.
 */
@WebFluxTest(PremiumAggregateController.class)
@Import(SecurityConfig.class)
class PremiumAggregateControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private PremiumAggregateService service;

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END   = LocalDate.of(2026, 8, 1);

    @Test
    void earnedPremium_defaultsToTenantDimension() {
        when(service.earnedPremium(eq(START), eq(END), eq(Dimension.TENANT), isNull(), isNull()))
                .thenReturn(Mono.just(List.of(
                        new PremiumEarnedAggregateRow(null, null, null, "USD",
                                new BigDecimal("1500.00"), 3L))));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/premium-earned")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].currencyCode").isEqualTo("USD")
                .jsonPath("$[0].earnedPremium").isEqualTo(1500.00)
                .jsonPath("$[0].rowCount").isEqualTo(3);

        verify(service).earnedPremium(eq(START), eq(END), eq(Dimension.TENANT), isNull(), isNull());
    }

    @Test
    void earnedPremium_lineDimension_passesThroughAndFiltersByLine() {
        when(service.earnedPremium(eq(START), eq(END), eq(Dimension.LINE), eq("HEALTH"), isNull()))
                .thenReturn(Mono.just(List.of(
                        new PremiumEarnedAggregateRow(null, null, "HEALTH", "USD",
                                new BigDecimal("900.00"), 2L),
                        new PremiumEarnedAggregateRow(null, null, "HEALTH", "ZWL",
                                new BigDecimal("450000.00"), 1L))));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/premium-earned")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .queryParam("dimension",   "LINE")
                        .queryParam("insuranceLine", "HEALTH")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].insuranceLine").isEqualTo("HEALTH")
                .jsonPath("$[1].currencyCode").isEqualTo("ZWL");
    }

    @Test
    void earnedPremium_schemeDimension_returnsSchemeIdAndName() {
        UUID schemeId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(service.earnedPremium(eq(START), eq(END), eq(Dimension.SCHEME), isNull(), isNull()))
                .thenReturn(Mono.just(List.of(
                        new PremiumEarnedAggregateRow(schemeId, "Gold", "HEALTH", "USD",
                                new BigDecimal("750.00"), 4L))));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/premium-earned")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .queryParam("dimension",   "SCHEME")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].schemeId").isEqualTo(schemeId.toString())
                .jsonPath("$[0].schemeName").isEqualTo("Gold");
    }

    @Test
    void earnedPremium_schemeIdFilter_passesThroughUnderSchemeDimension() {
        UUID schemeId = UUID.fromString("22222222-3333-4444-5555-666666666666");
        when(service.earnedPremium(eq(START), eq(END), eq(Dimension.SCHEME), isNull(), eq(schemeId)))
                .thenReturn(Mono.just(List.of()));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/premium-earned")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .queryParam("dimension",   "SCHEME")
                        .queryParam("schemeId",    schemeId.toString())
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);

        verify(service).earnedPremium(eq(START), eq(END), eq(Dimension.SCHEME), isNull(), eq(schemeId));
    }

    @Test
    void earnedPremium_emptyResult_returnsEmptyArray() {
        when(service.earnedPremium(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of()));

        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/premium-earned")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void earnedPremium_missingPeriodStart_returns400() {
        webTestClient.mutateWith(mockJwt()).get()
                .uri(uri -> uri.path("/api/v1/reports/aggregate/premium-earned")
                        .queryParam("periodEnd", "2026-08-01")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
