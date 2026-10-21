package com.medfund.shared.report;

import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behaviour contract for {@link ReportEnvelopeBuilder}. Only exercises the
 * pre-computed-aggregate variant ({@code build(...)} accepting a
 * {@code Mono<Map>}) — the SQL-driven variant needs a DatabaseClient which
 * only the per-controller ITs get to wire up.
 */
class ReportEnvelopeBuilderTest {

    private final ReportingCurrencyResolver currencyResolver = mock(ReportingCurrencyResolver.class);
    private final FxRateReader fxRateReader = mock(FxRateReader.class);
    private final ReportEnvelopeBuilder builder = new ReportEnvelopeBuilder(
            currencyResolver, fxRateReader, /* DatabaseClient */ null);

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void build_populatesEnvelopeWithRatesForEveryNativeCurrency() {
        when(currencyResolver.resolve(eq(TENANT), any())).thenReturn(Mono.just("USD"));
        when(fxRateReader.findRate(eq("ZWL"), eq("USD"), any(), eq(TENANT)))
                .thenReturn(Mono.just(new BigDecimal("0.00274")));
        when(fxRateReader.findRate(eq("USD"), eq("USD"), any(), eq(TENANT)))
                .thenReturn(Mono.just(BigDecimal.ONE));

        Map<String, PerCurrencyTotal> perCurrency = Map.of(
                "USD", new PerCurrencyTotal(new BigDecimal("100.00"), 3L),
                "ZWL", new PerCurrencyTotal(new BigDecimal("36500.00"), 2L));

        Mono<ReportResponse<String>> result = builder.build(
                ReportKey.CREDITORS, null, null,
                Mono.just("PAGE_PAYLOAD"),
                Mono.just(perCurrency));

        StepVerifier.create(result.contextWrite(withTenant(TENANT)))
                .assertNext(envelope -> {
                    assertThat(envelope.reportKey()).isEqualTo(ReportKey.CREDITORS.name());
                    assertThat(envelope.reportingCurrency()).isEqualTo("USD");
                    assertThat(envelope.data()).isEqualTo("PAGE_PAYLOAD");
                    assertThat(envelope.perCurrency()).containsKeys("USD", "ZWL");
                    assertThat(envelope.fxRates()).containsKeys("USD", "ZWL");
                    assertThat(envelope.warnings()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void build_warnsButSucceedsWhenAnFxRateIsMissing() {
        when(currencyResolver.resolve(eq(TENANT), any())).thenReturn(Mono.just("USD"));
        when(fxRateReader.findRate(eq("USD"), eq("USD"), any(), eq(TENANT)))
                .thenReturn(Mono.just(BigDecimal.ONE));
        // Missing rate for ZAR — best-effort per G28.
        when(fxRateReader.findRate(eq("ZAR"), eq("USD"), any(), eq(TENANT)))
                .thenReturn(Mono.empty());

        Map<String, PerCurrencyTotal> perCurrency = Map.of(
                "USD", new PerCurrencyTotal(new BigDecimal("100.00"), 3L),
                "ZAR", new PerCurrencyTotal(new BigDecimal("1750.00"), 5L));

        Mono<ReportResponse<String>> result = builder.build(
                ReportKey.CREDITORS,
                new ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                        ReportPeriod.PeriodGrain.MONTHLY),
                null,
                Mono.just("PAGE"),
                Mono.just(perCurrency));

        StepVerifier.create(result.contextWrite(withTenant(TENANT)))
                .assertNext(envelope -> {
                    assertThat(envelope.reportingCurrency()).isEqualTo("USD");
                    assertThat(envelope.fxRates()).containsOnlyKeys("USD");
                    assertThat(envelope.warnings()).anyMatch(w -> w.contains("ZAR"));
                })
                .verifyComplete();
    }

    @Test
    void build_overrideCurrencyWinsOverTenantDefault() {
        when(currencyResolver.resolve(eq(TENANT), eq("ZAR"))).thenReturn(Mono.just("ZAR"));
        when(fxRateReader.findRate(eq("ZAR"), eq("ZAR"), any(), eq(TENANT)))
                .thenReturn(Mono.just(BigDecimal.ONE));

        Mono<ReportResponse<String>> result = builder.build(
                ReportKey.CREDITORS, null, "ZAR",
                Mono.just("PAGE"),
                Mono.just(Map.of("ZAR", new PerCurrencyTotal(new BigDecimal("50"), 1L))));

        StepVerifier.create(result.contextWrite(withTenant(TENANT)))
                .assertNext(envelope -> assertThat(envelope.reportingCurrency()).isEqualTo("ZAR"))
                .verifyComplete();
    }

    @Test
    void buildNoAggregate_returnsEmptyPerCurrencyAndFxRates() {
        when(currencyResolver.resolve(eq(TENANT), any())).thenReturn(Mono.just("USD"));

        Mono<ReportResponse<String>> result = builder.buildNoAggregate(
                ReportKey.CLAIMS_SUMMARY, null, null, Mono.just("SUMMARY"));

        StepVerifier.create(result.contextWrite(withTenant(TENANT)))
                .assertNext(envelope -> {
                    assertThat(envelope.data()).isEqualTo("SUMMARY");
                    assertThat(envelope.perCurrency()).isEmpty();
                    assertThat(envelope.fxRates()).isEmpty();
                    assertThat(envelope.warnings()).isEmpty();
                })
                .verifyComplete();
    }

    private static Context withTenant(UUID tenantId) {
        return TenantContext.put(Context.empty(), tenantId.toString());
    }
}
