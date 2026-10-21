package com.medfund.shared.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportResponseTest {

    @Test
    void of_populatesEnvelopeFields() {
        var period = new ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                ReportPeriod.PeriodGrain.MONTHLY);
        var data = Map.of("total", new BigDecimal("100.00"));
        var perCurrency = Map.of(
                "USD", new PerCurrencyTotal(new BigDecimal("100.00"), 5L),
                "ZWL", new PerCurrencyTotal(new BigDecimal("36500.00"), 3L));
        var fxRates = Map.of("ZWL", new BigDecimal("0.00274"));
        var warnings = List.of("FX not available for ZAR→USD as of 2026-08-31");

        var response = ReportResponse.of(ReportKey.AGED_DEBTORS, period, "USD",
                data, perCurrency, fxRates, warnings);

        assertThat(response.reportKey()).isEqualTo("AGED_DEBTORS");
        assertThat(response.period()).isSameAs(period);
        assertThat(response.reportingCurrency()).isEqualTo("USD");
        assertThat(response.data()).isSameAs(data);
        assertThat(response.perCurrency()).containsKeys("USD", "ZWL");
        assertThat(response.perCurrency().get("USD").rowCount()).isEqualTo(5L);
        assertThat(response.fxRates()).containsEntry("ZWL", new BigDecimal("0.00274"));
        assertThat(response.warnings()).containsExactly("FX not available for ZAR→USD as of 2026-08-31");
        assertThat(response.generatedAt()).isNotNull();
    }

    @Test
    void of_defaultsNullMapsAndListToEmpty() {
        var response = ReportResponse.of(ReportKey.CREDITORS, null, "USD",
                Map.of(), null, null, null);

        assertThat(response.period()).isNull();
        assertThat(response.perCurrency()).isEmpty();
        assertThat(response.fxRates()).isEmpty();
        assertThat(response.warnings()).isEmpty();
    }
}
