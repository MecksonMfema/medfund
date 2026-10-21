package com.medfund.finance.regulatory.service;

import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fail-loud FX helper for Phase 16 regulator report shapers. Wraps
 * {@link FxRateReader} to guarantee a missing rate never silently
 * yields zero on a regulator submission — a difference from Phase 15
 * G28 which resolves missing FX to a best-effort envelope warning.
 *
 * <p>{@link FxRateReader#convert(BigDecimal, String, String, LocalDate, UUID)}
 * already fails loud with {@link com.medfund.shared.report.ReportGenerationException};
 * this class translates the generic error into
 * {@link RegulatoryReportGenerationException} with the report key +
 * tenant + as-of date attached so a compliance officer investigating a
 * failed export can identify which return + which currency pair broke.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegulatoryFxPolicy {

    private final FxRateReader fxRateReader;

    /**
     * Convert {@code amount} from {@code fromCurrency} to the report's
     * required currency, fail-loud with a regulator-scoped exception on
     * missing rate. Null / same-currency short-circuit.
     */
    public Mono<BigDecimal> convert(ReportKey reportKey,
                                    UUID tenantId,
                                    LocalDate asOf,
                                    BigDecimal amount,
                                    String fromCurrency,
                                    String toCurrency) {
        if (amount == null) return Mono.just(BigDecimal.ZERO);
        if (fromCurrency == null || toCurrency == null || fromCurrency.equals(toCurrency)) {
            return Mono.just(amount);
        }
        return fxRateReader.convert(amount, fromCurrency, toCurrency, asOf, tenantId)
                .onErrorMap(err -> new RegulatoryReportGenerationException(
                        String.format(
                                "FX rate missing for %s: cannot convert %s→%s as of %s (tenant %s)",
                                reportKey.name(), fromCurrency, toCurrency, asOf, tenantId),
                        err));
    }
}
