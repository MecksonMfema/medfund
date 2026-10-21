package com.medfund.claims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-app HTTP tests for the CLAIMS_SUMMARY report surface in
 * {@code ClaimsReportController} — schemes/providers/groups/members
 * summaries, scheme detail drill-down, XLSX exports, and the
 * {@code @RequiresReport} 403 gate (plan §16 ClaimsReportControllerIT).
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class ClaimsReportControllerIT extends AbstractClaimsReportIT {

    private static final LocalDate JUN_10 = LocalDate.of(2026, 6, 10);

    @Test
    void schemesReport_returnsSummaryAndPerCurrency() {
        UUID s1 = seedScheme("Clinical");
        UUID s2 = seedScheme("Maternity");
        UUID p1 = seedProvider("St Mary's");
        UUID p2 = seedProvider("Sunrise Clinic");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID m2 = seedMember("Grace", "Hopper", seedGroup("SME"), s2);
        seedExchangeRate("ZMW", "USD", new BigDecimal("0.2500000000"), LocalDate.of(2026, 6, 30));

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("2000.0000"), new BigDecimal("1800.0000"), new BigDecimal("1600.0000"),
                LocalDate.of(2026, 6, 3), JUN_10, JUN_10);
        seedClaim(m2, p2, s2, "paid", "ZMW",
                new BigDecimal("5000.0000"), new BigDecimal("4500.0000"), new BigDecimal("4000.0000"),
                LocalDate.of(2026, 6, 5), JUN_10, JUN_10);

        JsonNode envelope = getJson("/api/v1/reports/claims/schemes?periodStart=2026-06-01&periodEnd=2026-06-30");

        assertEquals("CLAIMS_SUMMARY", envelope.get("reportKey").asText());
        assertEquals("USD", envelope.get("reportingCurrency").asText());
        assertEquals(0, envelope.get("warnings").size());
        assertEquals(2, envelope.get("data").size());

        JsonNode clinical = envelope.get("data").get(0);
        assertEquals(s1.toString(), clinical.get("dimensionId").asText());
        assertEquals("Clinical", clinical.get("dimensionName").asText());
        assertEquals("USD", clinical.get("currencyCode").asText());
        assertEquals(2, clinical.get("claimCount").asLong());
        assertDecimal("3000.0000", clinical.get("totalClaimed"));
        assertDecimal("2600.0000", clinical.get("totalApproved"));
        assertDecimal("2350.0000", clinical.get("totalPaid"));

        JsonNode maternity = envelope.get("data").get(1);
        assertEquals(s2.toString(), maternity.get("dimensionId").asText());
        assertEquals("ZMW", maternity.get("currencyCode").asText());
        assertDecimal("5000.0000", maternity.get("totalClaimed"));
        assertDecimal("4000.0000", maternity.get("totalPaid"));

        assertEquals(2, envelope.get("perCurrency").size());
        assertDecimal("7950.0000", envelope.get("perCurrency").get("USD").get("totalAmount"));
        assertEquals(2, envelope.get("perCurrency").get("USD").get("rowCount").asLong());
        assertDecimal("13500.0000", envelope.get("perCurrency").get("ZMW").get("totalAmount"));
        assertEquals(1, envelope.get("perCurrency").get("ZMW").get("rowCount").asLong());
    }

    @Test
    void schemesReport_respectsInsuranceLineFilter() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID m2 = seedMember("Grace", "Hopper", seedGroup("Corporate"), s1);

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10, "HEALTH");
        seedClaim(m2, p1, s1, "paid", "USD",
                new BigDecimal("2000.0000"), new BigDecimal("1800.0000"), new BigDecimal("1500.0000"),
                LocalDate.of(2026, 6, 2), JUN_10, JUN_10, "DENTAL");

        JsonNode filtered = getJson("/api/v1/reports/claims/schemes"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30&insuranceLine=HEALTH");
        assertEquals(1, filtered.get("data").size());
        assertEquals(1, filtered.get("data").get(0).get("claimCount").asLong());
    }

    @Test
    void schemesReport_whenDisabled_returns403() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("100.0000"), new BigDecimal("80.0000"), new BigDecimal("70.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);

        setReportDisabled("CLAIMS_SUMMARY");
        try {
            get("/api/v1/reports/claims/schemes?periodStart=2026-06-01&periodEnd=2026-06-30")
                    .expectStatus().isForbidden();
        } finally {
            setReportEnabled("CLAIMS_SUMMARY");
        }
    }

    @Test
    void schemesReport_withReportingCurrency_convertsTotals() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");
        seedExchangeRate("ZMW", "USD", new BigDecimal("0.2500000000"), LocalDate.of(2026, 6, 15));

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);
        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("2000.0000"), new BigDecimal("1500.0000"), new BigDecimal("1200.0000"),
                LocalDate.of(2026, 6, 2), JUN_10, JUN_10);

        JsonNode envelope = getJson("/api/v1/reports/claims/schemes"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30&reportingCurrency=USD");

        assertEquals("USD", envelope.get("reportingCurrency").asText());
        assertDecimal("0.25", envelope.get("fxRates").get("ZMW"));
        assertEquals(2, envelope.get("data").size());
        JsonNode zmwNative = envelope.get("data").get(1);
        assertEquals("ZMW", zmwNative.get("currencyCode").asText());
        assertDecimal("2000.0000", zmwNative.get("totalClaimed"));
        assertDecimal("1500.0000", zmwNative.get("totalApproved"));
        assertDecimal("1200.0000", zmwNative.get("totalPaid"));
    }

    @Test
    void schemeDetail_returnsLedgerAndBuckets() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);
        seedClaim(m1, p1, s1, "submitted", "USD",
                new BigDecimal("2000.0000"), new BigDecimal("1800.0000"), new BigDecimal("1600.0000"),
                LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6));

        JsonNode envelope = getJson("/api/v1/reports/claims/schemes/" + s1
                + "?periodStart=2026-06-01&periodEnd=2026-07-31");
        JsonNode data = envelope.get("data");

        assertEquals(s1.toString(), data.get("dimensionId").asText());
        assertEquals("Clinical", data.get("dimensionName").asText());
        assertEquals(2, data.get("monthlyBuckets").size());
        assertEquals("2026-06-01", data.get("monthlyBuckets").get(0).get("month").asText());
        assertEquals(2, data.get("claims").get("total").asLong());
        assertEquals(2, data.get("claims").get("content").size());
        assertEquals("Ada Lovelace", data.get("claims").get("content").get(0).get("memberName").asText());
        assertEquals("St Mary's", data.get("claims").get("content").get(0).get("providerName").asText());
    }

    @Test
    void membersReport_returnsPerMemberRows() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID m2 = seedMember("Grace", "Hopper", seedGroup("SME"), s1);

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);
        seedClaim(m2, p1, s1, "paid", "USD",
                new BigDecimal("400.0000"), new BigDecimal("300.0000"), new BigDecimal("250.0000"),
                LocalDate.of(2026, 6, 2), JUN_10, JUN_10);

        JsonNode envelope = getJson("/api/v1/reports/claims/members"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30");
        assertEquals(2, envelope.get("data").get("content").size());
        assertEquals(2, envelope.get("data").get("total").asLong());
        assertDecimal("3500.0000", envelope.get("perCurrency").get("USD").get("totalAmount"));
        assertEquals(2, envelope.get("perCurrency").get("USD").get("rowCount").asLong());
    }

    @Test
    void schemesExportExcel_producesWorkbookAndAuditEvent() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);

        byte[] bytes = getExcelBytes("/api/v1/reports/claims/schemes/export/excel"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30");
        assertTrue(bytes.length > 0, "workbook body should be non-empty");
        // XLSX is a ZIP container — magic bytes PK.
        assertEquals((byte) 0x50, bytes[0]);
        assertEquals((byte) 0x4B, bytes[1]);

        assertTrue(securityEventPublished("CLAIMS_SUMMARY", Duration.ofSeconds(10)),
                "export should emit a DATA_ACCESS security event");
    }

    @Test
    void export_whenDisabled_returns403() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("100.0000"), new BigDecimal("80.0000"), new BigDecimal("70.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);

        setReportDisabled("CLAIMS_SUMMARY");
        try {
            get("/api/v1/reports/claims/schemes/export/excel"
                    + "?periodStart=2026-06-01&periodEnd=2026-06-30")
                    .expectStatus().isForbidden();
        } finally {
            setReportEnabled("CLAIMS_SUMMARY");
        }
    }
}
