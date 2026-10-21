package com.medfund.shared.report;

import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reactive helper that composes the four axes of a report envelope in one
 * call: the reporting-currency resolve, the payload {@code Mono<T>}, the
 * filtered-set per-currency aggregate SQL (G18), and the best-effort
 * native→reporting FX lookup (G28). Every retrofit controller uses this so
 * the four-step compose isn't re-hand-rolled per endpoint.
 *
 * <p>The per-currency aggregate SQL must be filtered by the same WHERE
 * clause as the paged payload — this is what makes the totals "filtered-set
 * totals" per G18 rather than global totals. Pass the same bindings.
 *
 * <p>Example — paginated creditors list:
 *
 * <pre>{@code
 * Mono<PageResponse<CreditorRow>> data = service.searchPaged(params);
 * String aggSql = """
 *     SELECT currency_code, SUM(outstanding_balance) AS total_amount, COUNT(*) AS row_count
 *       FROM provider_balances
 *      WHERE (:currency IS NULL OR currency_code = :currency)
 *      GROUP BY currency_code
 *     """;
 * return envelopeBuilder.build(
 *     ReportKey.CREDITORS,
 *     null,                          // no period on this report
 *     reportingCurrency,             // request param
 *     data,
 *     aggSql,
 *     spec -> spec.bind("currency", params.currencyCode()));
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportEnvelopeBuilder {

    private final ReportingCurrencyResolver currencyResolver;
    private final FxRateReader fxRateReader;
    private final DatabaseClient databaseClient;

    /**
     * Build the fully-populated envelope. Runs the aggregate SQL and the
     * FX lookups reactively — no blocking.
     */
    public <T> Mono<ReportResponse<T>> build(
            ReportKey key,
            ReportPeriod period,
            String overrideCurrency,
            Mono<T> dataMono,
            String perCurrencyAggregateSql,
            Consumer<DatabaseClient.GenericExecuteSpec> sqlBindings) {

        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseTenantId(tenantIdStr);

            Mono<String> currencyMono = currencyResolver.resolve(tenantId, overrideCurrency);
            Mono<Map<String, PerCurrencyTotal>> perCurrencyMono =
                    perCurrencyTotals(perCurrencyAggregateSql, sqlBindings);

            return Mono.zip(currencyMono, dataMono, perCurrencyMono)
                    .flatMap(tuple -> {
                        String reportingCurrency = tuple.getT1();
                        T data = tuple.getT2();
                        Map<String, PerCurrencyTotal> perCurrency = tuple.getT3();
                        return bestEffortFxRates(perCurrency.keySet(), reportingCurrency,
                                        tenantId, asOf(period))
                                .map(rw -> ReportResponse.of(
                                        key, period, reportingCurrency, data,
                                        perCurrency, rw.rates(), rw.warnings()));
                    });
        });
    }

    /**
     * Envelope variant that takes a pre-computed per-currency totals map
     * instead of a raw SQL string. Use this when the paged query is
     * dynamic (UNION branches, template-driven WHERE fragments) and it's
     * simpler to compute the aggregate inside the repository than to
     * describe it as one SQL string — for example
     * {@code CreditorQueryRepository} which unions
     * {@code provider_balances} + {@code member_balances}.
     */
    public <T> Mono<ReportResponse<T>> build(
            ReportKey key,
            ReportPeriod period,
            String overrideCurrency,
            Mono<T> dataMono,
            Mono<Map<String, PerCurrencyTotal>> perCurrencyMono) {

        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseTenantId(tenantIdStr);

            return Mono.zip(
                            currencyResolver.resolve(tenantId, overrideCurrency),
                            dataMono,
                            perCurrencyMono)
                    .flatMap(tuple -> {
                        String reportingCurrency = tuple.getT1();
                        T data = tuple.getT2();
                        Map<String, PerCurrencyTotal> perCurrency = tuple.getT3();
                        return bestEffortFxRates(perCurrency.keySet(), reportingCurrency,
                                        tenantId, asOf(period))
                                .map(rw -> ReportResponse.of(
                                        key, period, reportingCurrency, data,
                                        perCurrency, rw.rates(), rw.warnings()));
                    });
        });
    }

    /**
     * Envelope without a per-currency aggregate — for grand-total scalar
     * reports where perCurrency is either empty (single-currency report) or
     * computed inside the payload itself. Still resolves reporting currency
     * and builds the empty perCurrency map so the wire shape stays uniform.
     */
    public <T> Mono<ReportResponse<T>> buildNoAggregate(
            ReportKey key,
            ReportPeriod period,
            String overrideCurrency,
            Mono<T> dataMono) {
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseTenantId(tenantIdStr);
            return Mono.zip(currencyResolver.resolve(tenantId, overrideCurrency), dataMono)
                    .map(tuple -> ReportResponse.of(
                            key, period, tuple.getT1(), tuple.getT2(),
                            Map.of(), Map.of(), List.of()));
        });
    }

    private Mono<Map<String, PerCurrencyTotal>> perCurrencyTotals(
            String sql, Consumer<DatabaseClient.GenericExecuteSpec> bindings) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql);
        if (bindings != null) bindings.accept(spec);
        return spec.map((row, meta) -> {
                    String cc = row.get("currency_code", String.class);
                    BigDecimal amt = row.get("total_amount", BigDecimal.class);
                    Long rc = row.get("row_count", Long.class);
                    return Map.entry(
                            cc != null ? cc : "",
                            new PerCurrencyTotal(
                                    amt != null ? amt : BigDecimal.ZERO,
                                    rc != null ? rc  : 0L));
                })
                .all()
                .filter(e -> !e.getKey().isBlank())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .onErrorResume(err -> {
                    log.warn("[report-envelope] perCurrency aggregate failed: {}", err.getMessage());
                    return Mono.just(Map.of());
                });
    }

    private Mono<RatesAndWarnings> bestEffortFxRates(Set<String> nativeCurrencies,
                                                     String reportingCurrency, UUID tenantId,
                                                     LocalDate asOf) {
        if (nativeCurrencies == null || nativeCurrencies.isEmpty()) {
            return Mono.just(new RatesAndWarnings(Map.of(), List.of()));
        }
        Map<String, BigDecimal> rates = new HashMap<>();
        List<String> warnings = new ArrayList<>();

        return Flux.fromIterable(nativeCurrencies)
                .flatMap(nativeCcy -> fxRateReader.findRate(nativeCcy, reportingCurrency, asOf, tenantId)
                        .doOnNext(rate -> rates.put(nativeCcy, rate))
                        .switchIfEmpty(Mono.fromRunnable(() -> warnings.add(
                                "FX not available for " + nativeCcy + "->"
                                        + reportingCurrency + " as of " + asOf)))
                        .then(Mono.just(nativeCcy)))
                .then(Mono.fromSupplier(() -> new RatesAndWarnings(rates, warnings)));
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try {
            return UUID.fromString(tenantIdStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate asOf(ReportPeriod period) {
        return period != null && period.periodEnd() != null ? period.periodEnd() : LocalDate.now();
    }

    private record RatesAndWarnings(Map<String, BigDecimal> rates, List<String> warnings) {}
}
