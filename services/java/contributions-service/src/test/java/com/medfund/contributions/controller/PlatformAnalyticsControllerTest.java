package com.medfund.contributions.controller;

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
 * Focused unit tests for the FX-conversion + revenue-schema-attribution paths
 * of the Phase 9 cross-tenant platform analytics controller — the arithmetic
 * layer isolated from schema enumeration + DB access. Schema fanout belongs to
 * Testcontainers ITs.
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
    void convertToUsd_sameCurrency_shortCircuits() {
        Map<String, BigDecimal> cache = new HashMap<>();
        AtomicLong skipped = new AtomicLong();
        var raw = new PlatformAnalyticsController.RawRow(
                Instant.parse("2026-05-01T00:00:00Z"),
                new BigDecimal("100.00"),
                "USD",
                null);

        StepVerifier.create(controller.convertToUsd(raw, cache, skipped))
                .assertNext(row -> {
                    assertThat(row).containsEntry("ts", "2026-05-01T00:00:00Z");
                    assertThat(row.get("value")).isEqualTo(new BigDecimal("100.00"));
                })
                .verifyComplete();
        verifyNoMoreInteractions(fxRateReader);
    }

    @Test
    void convertToUsd_nonUsdCachesLookup() {
        when(fxRateReader.findRate(eq("EUR"), eq("USD"), any(LocalDate.class), any()))
                .thenReturn(Mono.just(new BigDecimal("1.10")));
        Map<String, BigDecimal> cache = new HashMap<>();
        AtomicLong skipped = new AtomicLong();

        var raw1 = new PlatformAnalyticsController.RawRow(
                Instant.parse("2026-05-01T00:00:00Z"), new BigDecimal("100"), "EUR", null);
        var raw2 = new PlatformAnalyticsController.RawRow(
                Instant.parse("2026-05-01T18:00:00Z"), new BigDecimal("200"), "EUR", null);

        StepVerifier.create(controller.convertToUsd(raw1, cache, skipped))
                .assertNext(row -> assertThat(row.get("value")).isEqualTo(new BigDecimal("110.00")))
                .verifyComplete();
        StepVerifier.create(controller.convertToUsd(raw2, cache, skipped))
                .assertNext(row -> assertThat(row.get("value")).isEqualTo(new BigDecimal("220.00")))
                .verifyComplete();

        verify(fxRateReader).findRate(eq("EUR"), eq("USD"), any(LocalDate.class), any());
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
                new BigDecimal("50"),
                "XYZ",
                null);

        StepVerifier.create(controller.convertToUsd(raw, cache, skipped))
                .verifyComplete();
        assertThat(skipped.get()).isEqualTo(1);
    }

    @Test
    void convertToUsdWithSchema_tagsSchemaOnResult() {
        when(fxRateReader.findRate(eq("EUR"), eq("USD"), any(LocalDate.class), any()))
                .thenReturn(Mono.just(new BigDecimal("1.10")));
        Map<String, BigDecimal> cache = new HashMap<>();
        AtomicLong skipped = new AtomicLong();
        var raw = new PlatformAnalyticsController.RawRow(
                Instant.parse("2026-05-01T00:00:00Z"),
                new BigDecimal("100"),
                "EUR",
                "tenant_alpha");

        StepVerifier.create(controller.convertToUsdWithSchema(raw, cache, skipped))
                .assertNext(sa -> {
                    assertThat(sa.schema()).isEqualTo("tenant_alpha");
                    assertThat(sa.amount()).isEqualTo(new BigDecimal("110.00"));
                })
                .verifyComplete();
    }
}
