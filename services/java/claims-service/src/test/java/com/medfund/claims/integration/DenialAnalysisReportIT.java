package com.medfund.claims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-app HTTP tests for the DENIAL_ANALYSIS report (G47): the four views
 * over the REJECTED claim set (category / code / provider / monthly trend),
 * provider denial-rate share, multi-month trend gating, export + audit,
 * and the 403 gate (plan §16 DenialAnalysisReportIT).
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class DenialAnalysisReportIT extends AbstractClaimsReportIT {

    private static final LocalDate JUN_10 = LocalDate.of(2026, 6, 10);

    @Test
    void denialAnalysis_composesFourViews() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID p2 = seedProvider("Sunrise Clinic");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID m2 = seedMember("Grace", "Hopper", seedGroup("Corporate"), s1);

        UUID r01a = seedClaim(m1, p1, s1, "REJECTED", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 5), JUN_10, JUN_10);
        UUID r02a = seedClaim(m1, p1, s1, "REJECTED", "USD",
                new BigDecimal("2000.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 15));
        UUID paid = seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("500.0000"), new BigDecimal("450.0000"), new BigDecimal("400.0000"),
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 20));
        UUID r01b = seedClaim(m2, p2, s1, "REJECTED", "USD",
                new BigDecimal("4000.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 5));
        UUID r02b = seedClaim(m2, p2, s1, "REJECTED", "USD",
                new BigDecimal("3000.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 10));
        setRejectionReason(r01a, "R01");
        setRejectionReason(r02a, "R02");
        setRejectionReason(r01b, "R01");
        setRejectionReason(r02b, "R02");

        JsonNode envelope = getJson("/api/v1/reports/claims/denial-analysis"
                + "?periodStart=2026-06-01&periodEnd=2026-07-31");
        JsonNode data = envelope.get("data");

        assertEquals("DENIAL_ANALYSIS", envelope.get("reportKey").asText());

        // Category view — grouped by rejection_reasons.category.
        assertEquals(2, data.get("byCategory").size());
        JsonNode eligibility = data.get("byCategory").get(0);
        assertEquals("ELIGIBILITY", eligibility.get("category").asText());
        assertEquals(2, eligibility.get("claimCount").asLong());
        assertDecimal("5000.0000", eligibility.get("totalClaimed"));
        assertEquals("WAITING_PERIOD", data.get("byCategory").get(1).get("category").asText());

        // Code view — ordered by category then code.
        assertEquals(2, data.get("byCode").size());
        JsonNode r01 = data.get("byCode").get(0);
        assertEquals("R01", r01.get("code").asText());
        assertEquals("ELIGIBILITY", r01.get("category").asText());
        assertDecimal("5000.0000", r01.get("totalClaimed"));
        assertEquals("R02", data.get("byCode").get(1).get("code").asText());

        // Provider view — denominator = all window claims for the provider.
        assertEquals(2, data.get("byProvider").size());
        JsonNode p1Row = findByProvider(data.get("byProvider"), p1);
        assertEquals(2, p1Row.get("claimCount").asLong());
        assertDecimal("3000.0000", p1Row.get("totalClaimed"));
        assertDecimal("66.67", p1Row.get("denialRatePct"));
        JsonNode p2Row = findByProvider(data.get("byProvider"), p2);
        assertEquals(2, p2Row.get("claimCount").asLong());
        assertDecimal("7000.0000", p2Row.get("totalClaimed"));
        assertDecimal("100.00", p2Row.get("denialRatePct"));

        // Monthly trend — multi-month window only (G47).
        assertEquals(2, data.get("monthlyTrend").size());
        assertEquals("2026-06-01", data.get("monthlyTrend").get(0).get("month").asText());
        assertEquals(2, data.get("monthlyTrend").get(0).get("claimCount").asLong());
        assertDecimal("3000.0000", data.get("monthlyTrend").get(0).get("totalClaimed"));
        assertEquals("2026-07-01", data.get("monthlyTrend").get(1).get("month").asText());
        assertEquals(2, data.get("monthlyTrend").get(1).get("claimCount").asLong());
    }

    @Test
    void denialAnalysis_singleMonthWindow_rendersEmptyTrend() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID rejected = seedClaim(m1, p1, s1, "REJECTED", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 5), JUN_10, JUN_10);
        setRejectionReason(rejected, "R01");

        JsonNode envelope = getJson("/api/v1/reports/claims/denial-analysis"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30");
        JsonNode data = envelope.get("data");
        assertEquals(1, data.get("byCategory").size());
        assertEquals(0, data.get("monthlyTrend").size());
    }

    @Test
    void denialAnalysis_filtersByCategoryWithoutShrinkingDenominator() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID r01 = seedClaim(m1, p1, s1, "REJECTED", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 5), JUN_10, JUN_10);
        UUID r02 = seedClaim(m1, p1, s1, "REJECTED", "USD",
                new BigDecimal("2000.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 15));
        setRejectionReason(r01, "R01");
        setRejectionReason(r02, "R02");

        JsonNode envelope = getJson("/api/v1/reports/claims/denial-analysis"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30&category=ELIGIBILITY");
        JsonNode data = envelope.get("data");
        assertEquals(1, data.get("byCode").size());
        assertEquals("R01", data.get("byCode").get(0).get("code").asText());

        // Denominator still the provider's full window count (both rejects).
        JsonNode provider = data.get("byProvider").get(0);
        assertEquals(1, provider.get("claimCount").asLong());
        assertDecimal("50.00", provider.get("denialRatePct"));
    }

    @Test
    void denialAnalysis_whenDisabled_returns403() {
        setReportDisabled("DENIAL_ANALYSIS");
        try {
            get("/api/v1/reports/claims/denial-analysis"
                    + "?periodStart=2026-06-01&periodEnd=2026-06-30")
                    .expectStatus().isForbidden();
        } finally {
            setReportEnabled("DENIAL_ANALYSIS");
        }
    }

    @Test
    void denialAnalysis_export_producesWorkbookAndAuditEvent() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID rejected = seedClaim(m1, p1, s1, "REJECTED", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 5), JUN_10, JUN_10);
        setRejectionReason(rejected, "R01");

        byte[] bytes = getExcelBytes("/api/v1/reports/claims/denial-analysis/export/excel"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30");
        assertEquals((byte) 0x50, bytes[0]);
        assertEquals((byte) 0x4B, bytes[1]);

        assertTrue(securityEventPublished("DENIAL_ANALYSIS", Duration.ofSeconds(10)),
                "export should emit a DATA_ACCESS security event");
    }

    private static JsonNode findByProvider(JsonNode rows, UUID providerId) {
        for (JsonNode row : rows) {
            if (providerId.toString().equals(row.get("providerId").asText())) {
                return row;
            }
        }
        throw new AssertionError("no provider row for " + providerId + " in " + rows);
    }
}
