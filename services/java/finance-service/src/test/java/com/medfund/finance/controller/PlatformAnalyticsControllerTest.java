package com.medfund.finance.controller;

import com.medfund.shared.report.FxRateReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the FX-cache + skipped-counter behaviour of the
 * Phase 9 platform analytics controller — the pure per-row conversion path.
 * Schema enumeration + full endpoint wiring belong to Testcontainers ITs;
 * this test isolates the arithmetic that the ITs would otherwise re-verify
 * per assertion.
 */
class PlatformAnalyticsControllerTest {

    private FxRateReader fxRateReader;
    private PlatformAnalyticsController controller;

    @BeforeEach
    void setUp() {
        fxRateReader = mock(FxRateReader.class);
        controller = new PlatformAnalyticsController(null, fxRateReader);
    }

    @Test
    void convertToUsd_sameCurrency_shortCircuitsWithoutFxLookup() {
        Map<String, BigDecimal> cache = new HashMap<>();
        AtomicLong skipped = new AtomicLong();
        var raw = new PlatformAnalyticsController.RawRow(
                Instant.parse("2026-05-01T00:00:00Z"),
                new BigDecimal("100.00"),
                "USD");

        StepVerifier.create(controller.convertToUsd(raw, cache, skipped))
                .assertNext(row -> {
                    assertThat(row).containsEntry("ts", "2026-05-01T00:00:00Z");
                    assertThat(row.get("value")).isEqualTo(new BigDecimal("100.00"));
                })
                .verifyComplete();
        assertThat(skipped.get()).isZero();
        verifyNoMoreInteractions(fxRateReader);
    }

    @Test
    void convertToUsd_nonUsdWithRate_multipliesAndCaches() {
        when(fxRateReader.findRate(eq("ZWL"), eq("USD"), any(LocalDate.class), any()))
                .thenReturn(Mono.just(new BigDecimal("0.001")));
        Map<String, BigDecimal> cache = new HashMap<>();
        AtomicLong skipped = new AtomicLong();
        var raw = new PlatformAnalyticsController.RawRow(
                Instant.parse("2026-05-01T00:00:00Z"),
                new BigDecimal("1000.00"),
                "ZWL");

        StepVerifier.create(controller.convertToUsd(raw, cache, skipped))
                .assertNext(row -> assertThat(row.get("value")).isEqualTo(new BigDecimal("1.00000")))
                .verifyComplete();
        assertThat(cache).containsKey("ZWL|2026-05-01");
        assertThat(skipped.get()).isZero();

        // Second row same day → cache hit, no additional FX lookup.
        StepVerifier.create(controller.convertToUsd(
                        new PlatformAnalyticsController.RawRow(
                                Instant.parse("2026-05-01T12:00:00Z"),
                                new BigDecimal("2000.00"),
                                "ZWL"),
                        cache, skipped))
                .assertNext(row -> assertThat(row.get("value")).isEqualTo(new BigDecimal("2.00000")))
                .verifyComplete();
        verify(fxRateReader).findRate(eq("ZWL"), eq("USD"), any(LocalDate.class), any());
        verifyNoMoreInteractions(fxRateReader);
    }

    @Test
    void convertToUsd_missingRate_incrementsSkippedAndDropsRow() {
        when(fxRateReader.findRate(eq("XYZ"), eq("USD"), any(LocalDate.class), any()))
                .thenReturn(Mono.empty());
        Map<String, BigDecimal> cache = new HashMap<>();
        AtomicLong skipped = new AtomicLong();
        var raw = new PlatformAnalyticsController.RawRow(
                Instant.parse("2026-05-01T00:00:00Z"),
                new BigDecimal("50.00"),
                "XYZ");

        StepVerifier.create(controller.convertToUsd(raw, cache, skipped))
                .verifyComplete();
        assertThat(skipped.get()).isEqualTo(1);
    }

    @Test
    void convertToUsd_nullRowFields_incrementsSkipped() {
        Map<String, BigDecimal> cache = new HashMap<>();
        AtomicLong skipped = new AtomicLong();
        StepVerifier.create(controller.convertToUsd(
                        new PlatformAnalyticsController.RawRow(null, BigDecimal.TEN, "USD"),
                        cache, skipped))
                .verifyComplete();
        assertThat(skipped.get()).isEqualTo(1);
    }
}
