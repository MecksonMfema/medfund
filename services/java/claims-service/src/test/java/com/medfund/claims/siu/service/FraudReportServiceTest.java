package com.medfund.claims.siu.service;

import com.medfund.claims.siu.dto.AiCalibrationData;
import com.medfund.claims.siu.dto.FraudReportData;
import com.medfund.claims.siu.dto.TrendPoint;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FraudReportService}. The reactive DatabaseClient +
 * ReportEnvelopeBuilder + FxRateReader wiring is exercised by
 * {@code FraudReportControllerIT} (deferred to a follow-up test-hardening
 * pass — see Phase 6 Deviations). This class covers:
 *
 * <ul>
 *   <li>the 6-tile {@link FraudReportData} record shape;</li>
 *   <li>the pure helpers — {@link FraudReportService#fillMissingMonths},
 *       {@link FraudReportService#finaliseCalibration},
 *       {@link FraudReportService#isSupervisorOrAdmin};</li>
 *   <li>and the {@link AiCalibrationData} small-N fallback per FR11.</li>
 * </ul>
 */
class FraudReportServiceTest {

    // ── FraudReportData record shape ────────────────────────────────────

    @Test
    void fraudReportData_carriesAllSixTilesPlusPerCurrencyMap() {
        FraudReportData data = new FraudReportData(
                10L, 7L,
                new BigDecimal("15000.00"),
                new BigDecimal("0.7000"),
                new BigDecimal("4.25"), // avgCycleTimeDays
                2L,                       // reopenedCount
                Map.of("USD", new BigDecimal("15000.00")));

        assertThat(data.casesOpened()).isEqualTo(10L);
        assertThat(data.confirmedCount()).isEqualTo(7L);
        assertThat(data.savingsComposite()).isEqualByComparingTo("15000.00");
        assertThat(data.confirmationRate()).isEqualByComparingTo("0.7000");
        assertThat(data.avgCycleTimeDays()).isEqualByComparingTo("4.25");
        assertThat(data.reopenedCount()).isEqualTo(2L);
        assertThat(data.savingsPerCurrency()).containsEntry("USD", new BigDecimal("15000.00"));
    }

    @Test
    void fraudReportData_zeroOpened_zeroRateIsRepresentable() {
        FraudReportData data = new FraudReportData(0L, 0L, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0L, Map.of());
        assertThat(data.confirmationRate()).isEqualByComparingTo("0");
        assertThat(data.casesOpened()).isZero();
        assertThat(data.reopenedCount()).isZero();
        assertThat(data.savingsPerCurrency()).isEmpty();
    }

    // ── trend backfill (zero-months) ────────────────────────────────────

    @Test
    void trendBackfill_fillsMissingMonthsWithZeros() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<TrendPoint> sparse = List.of(
                new TrendPoint("2026-06-01", 5, 2, 1),
                // 2026-07 missing on purpose
                new TrendPoint("2026-08-01", 3, 1, 0));
        List<TrendPoint> filled = FraudReportService.fillMissingMonths(sparse, start, 3);

        assertThat(filled).hasSize(3);
        assertThat(filled).extracting(TrendPoint::month)
                .containsExactly("2026-06-01", "2026-07-01", "2026-08-01");
        assertThat(filled.get(1).casesOpened()).isZero();
        assertThat(filled.get(1).confirmedCount()).isZero();
        assertThat(filled.get(1).dismissedCount()).isZero();
    }

    @Test
    void trendBackfill_emptyRowsProducesAllZeros() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<TrendPoint> filled = FraudReportService.fillMissingMonths(List.of(), start, 4);
        assertThat(filled).hasSize(4);
        assertThat(filled).allSatisfy(p -> {
            assertThat(p.casesOpened()).isZero();
            assertThat(p.confirmedCount()).isZero();
            assertThat(p.dismissedCount()).isZero();
        });
    }

    // ── AI calibration N < 50 fallback ──────────────────────────────────

    @Test
    void aiCalibration_belowMinN_returnsEmptyRowsAndWarning() {
        List<AiCalibrationData.CalibrationRow> rows = List.of(
                new AiCalibrationData.CalibrationRow("HIGH", 10, 5, 20, "0.6667"),
                new AiCalibrationData.CalibrationRow("MEDIUM", 12, 8, 30, "0.6000"));
        AiCalibrationData d = FraudReportService.finaliseCalibration(rows);
        assertThat(d.rows()).isEmpty();
        assertThat(d.warnings())
                .hasSize(1)
                .first().asString().contains("Insufficient data");
    }

    @Test
    void aiCalibration_atOrAboveMinN_returnsRowsAndNoWarnings() {
        // TP sum = 50 = MIN_CALIBRATION_N
        List<AiCalibrationData.CalibrationRow> rows = List.of(
                new AiCalibrationData.CalibrationRow("HIGH", 30, 10, 50, "0.7500"),
                new AiCalibrationData.CalibrationRow("MEDIUM", 20, 20, 60, "0.5000"));
        AiCalibrationData d = FraudReportService.finaliseCalibration(rows);
        assertThat(d.rows()).hasSize(2);
        assertThat(d.warnings()).isEmpty();
    }

    // ── investigator productivity role gate ─────────────────────────────

    @Test
    void isSupervisorOrAdmin_flagsSupervisor() {
        Jwt jwt = jwtWithRoles(List.of("siu_officer", "siu_supervisor"));
        assertThat(FraudReportService.isSupervisorOrAdmin(jwt)).isTrue();
    }

    @Test
    void isSupervisorOrAdmin_flagsTenantAdmin() {
        Jwt jwt = jwtWithRoles(List.of("tenant_admin"));
        assertThat(FraudReportService.isSupervisorOrAdmin(jwt)).isTrue();
    }

    @Test
    void isSupervisorOrAdmin_officerAloneReturnsFalse() {
        Jwt jwt = jwtWithRoles(List.of("siu_officer"));
        assertThat(FraudReportService.isSupervisorOrAdmin(jwt)).isFalse();
    }

    @Test
    void isSupervisorOrAdmin_nullJwtOrMissingRolesFalse() {
        assertThat(FraudReportService.isSupervisorOrAdmin(null)).isFalse();
        Jwt empty = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), Map.of("sub", "u"));
        assertThat(FraudReportService.isSupervisorOrAdmin(empty)).isFalse();
    }

    private static Jwt jwtWithRoles(List<String> roles) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "u",
                        "email", "u@medfund.local",
                        "realm_access", Map.of("roles", roles)));
    }
}
