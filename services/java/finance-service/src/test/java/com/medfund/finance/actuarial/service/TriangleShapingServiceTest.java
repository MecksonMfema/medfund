package com.medfund.finance.actuarial.service;

import com.medfund.finance.actuarial.service.TriangleShapingService.Grain;
import com.medfund.finance.actuarial.service.TriangleShapingService.Shape;
import com.medfund.finance.actuarial.service.TriangleShapingService.TriangleShapeRequest;
import com.medfund.finance.actuarial.service.TriangleShapingService.TriangleShapeResult;
import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.client.ClaimsClient.AdjudicatedClaimRow;
import com.medfund.finance.client.FxConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriangleShapingServiceTest {

    @Mock ClaimsClient claimsClient;
    @Mock FxConverter fxConverter;
    @InjectMocks TriangleShapingService service;

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void bucketsClaimsIntoCumulativeQuarterlyTriangle() {
        AdjudicatedClaimRow q1Service_q1Report = row("HEALTH", "USD",
                new BigDecimal("100.00"), LocalDate.of(2026, 1, 15), Instant.parse("2026-02-01T00:00:00Z"));
        AdjudicatedClaimRow q1Service_q2Report = row("HEALTH", "USD",
                new BigDecimal("50.00"), LocalDate.of(2026, 1, 20), Instant.parse("2026-04-15T00:00:00Z"));
        AdjudicatedClaimRow q2Service_q2Report = row("HEALTH", "USD",
                new BigDecimal("200.00"), LocalDate.of(2026, 4, 5), Instant.parse("2026-05-15T00:00:00Z"));

        when(claimsClient.streamAdjudicatedClaimsForBackfill(eq("HEALTH"), any(LocalDate.class), eq(100)))
                .thenReturn(Flux.just(q1Service_q1Report, q1Service_q2Report, q2Service_q2Report));
        // Same-currency → converter still called, but short-circuits to identity per FxConverter contract.
        when(fxConverter.convert(any(BigDecimal.class), anyString(), anyString(), any(LocalDate.class), any(UUID.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        TriangleShapeResult result = service.shape(new TriangleShapeRequest(
                TENANT,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
                "HEALTH",
                Shape.paid,
                Grain.quarter,
                "USD")).block();

        assertThat(result).isNotNull();
        assertThat(result.warnings()).isEmpty();
        Map<String, Object> triangle = result.triangle();
        assertThat(triangle.get("accident_periods")).isEqualTo(List.of("2026Q1", "2026Q2"));
        assertThat(triangle.get("development_periods")).isEqualTo(List.of("1", "2"));
        assertThat(triangle.get("grain")).isEqualTo("quarter");
        assertThat(triangle.get("reporting_currency")).isEqualTo("USD");
        assertThat(triangle.get("insurance_line")).isEqualTo("HEALTH");
        assertThat(triangle.get("shape")).isEqualTo("paid");

        @SuppressWarnings("unchecked")
        List<List<Object>> cells = (List<List<Object>>) triangle.get("cells");
        // 2026Q1 dev1 = 100 (service Jan15, report Feb1 = Q1 → dev delta 0)
        // 2026Q1 dev2 = 100 + 50 (cumulative — the extra 50 landed in Q2)
        assertThat((Double) cells.get(0).get(0)).isEqualTo(100.00);
        assertThat((Double) cells.get(0).get(1)).isEqualTo(150.00);
        // 2026Q2 dev1 = 200 (service Apr5, report May15 = Q2 → dev delta 0)
        // 2026Q2 dev2 masked as future (accident + dev > periodEnd, upper-right).
        assertThat((Double) cells.get(1).get(0)).isEqualTo(200.00);
        assertThat(cells.get(1).get(1)).isNull();
    }

    @Test
    void missingFxRateSkipsClaimAndAppendsWarning() {
        AdjudicatedClaimRow zwlClaim = row("HEALTH", "ZWL",
                new BigDecimal("5000.00"), LocalDate.of(2026, 1, 15), Instant.parse("2026-02-01T00:00:00Z"));
        AdjudicatedClaimRow usdClaim = row("HEALTH", "USD",
                new BigDecimal("100.00"), LocalDate.of(2026, 1, 20), Instant.parse("2026-02-15T00:00:00Z"));

        when(claimsClient.streamAdjudicatedClaimsForBackfill(anyString(), any(LocalDate.class), eq(100)))
                .thenReturn(Flux.just(zwlClaim, usdClaim));
        when(fxConverter.convert(any(BigDecimal.class), eq("USD"), eq("USD"), any(LocalDate.class), any(UUID.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(fxConverter.convert(any(BigDecimal.class), eq("ZWL"), eq("USD"), any(LocalDate.class), any(UUID.class)))
                .thenReturn(Mono.error(new IllegalStateException("No exchange rate for ZWL->USD")));

        TriangleShapeResult result = service.shape(new TriangleShapeRequest(
                TENANT,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                "HEALTH",
                Shape.paid,
                Grain.quarter,
                "USD")).block();

        assertThat(result).isNotNull();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("FX rate missing for ZWL->USD");
    }

    @Test
    void skipsClaimsOutsidePeriodOrWithZeroApprovedAmount() {
        AdjudicatedClaimRow beforePeriod = row("HEALTH", "USD",
                new BigDecimal("100.00"), LocalDate.of(2025, 12, 1), Instant.parse("2025-12-15T00:00:00Z"));
        AdjudicatedClaimRow zeroApproved = row("HEALTH", "USD",
                BigDecimal.ZERO, LocalDate.of(2026, 2, 1), Instant.parse("2026-02-15T00:00:00Z"));
        AdjudicatedClaimRow inPeriod = row("HEALTH", "USD",
                new BigDecimal("300.00"), LocalDate.of(2026, 2, 1), Instant.parse("2026-02-15T00:00:00Z"));

        when(claimsClient.streamAdjudicatedClaimsForBackfill(anyString(), any(LocalDate.class), eq(100)))
                .thenReturn(Flux.just(beforePeriod, zeroApproved, inPeriod));
        when(fxConverter.convert(any(BigDecimal.class), anyString(), anyString(), any(LocalDate.class), any(UUID.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        TriangleShapeResult result = service.shape(new TriangleShapeRequest(
                TENANT,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                "HEALTH",
                Shape.paid,
                Grain.quarter,
                "USD")).block();

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        List<List<Object>> cells = (List<List<Object>>) result.triangle().get("cells");
        // Single-quarter triangle → only cell[0][0] active, worth 300 from the surviving claim
        assertThat((Double) cells.get(0).get(0)).isEqualTo(300.00);
    }

    @Test
    void requestValidationRejectsInvertedPeriod() {
        StepVerifier.create(Mono.fromCallable(() -> new TriangleShapeRequest(
                        TENANT, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1),
                        "HEALTH", Shape.paid, Grain.month, "USD")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    private static AdjudicatedClaimRow row(String line, String currency, BigDecimal approved,
                                           LocalDate serviceDate, Instant submission) {
        return new AdjudicatedClaimRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                line, approved, currency,
                OffsetDateTime.ofInstant(submission, java.time.ZoneOffset.UTC),
                serviceDate,
                submission);
    }
}
