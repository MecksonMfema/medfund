package com.medfund.shared.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportResponseTest {

    @Test
    void of_populatesEnvelopeFields() {
        var period = new ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                ReportPeriod.PeriodGrain.MONTHLY);
        var data   = Map.of("total", new BigDecimal("100.00"));
        var native_ = Map.of("USD", data, "ZWL", Map.of("total", new BigDecimal("36500.00")));

        var response = ReportResponse.of(ReportKey.AGED_DEBTORS, period, "USD", data, native_);

        assertThat(response.reportKey()).isEqualTo("AGED_DEBTORS");
        assertThat(response.period()).isSameAs(period);
        assertThat(response.reportingCurrency()).isEqualTo("USD");
        assertThat(response.data()).isSameAs(data);
        assertThat(response.perCurrency()).containsKeys("USD", "ZWL");
        assertThat(response.generatedAt()).isNotNull();
    }
}
