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
 * Full-app HTTP tests for the CLAIMS_FREQUENCY_SEVERITY report (G48):
 * per-(scheme × insurance-line × currency) frequency / severity on the
 * service-date clock, the G48 exposure-proxy warning, export + audit, and
 * the 403 gate (plan §16 ClaimsFrequencySeverityIT).
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class ClaimsFrequencySeverityIT extends AbstractClaimsReportIT {

    private static final LocalDate JUN_10 = LocalDate.of(2026, 6, 10);

    @Test
    void frequencySeverity_returnsSchemeCellsWithExposureWarning() {
        UUID s1 = seedScheme("Clinical");
        UUID s2 = seedScheme("Maternity");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID m2 = seedMember("Grace", "Hopper", seedGroup("Corporate"), s1);
        UUID m3 = seedMember("Alan", "Turing", seedGroup("Corporate"), s2);

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("110.0000"), new BigDecimal("100.0000"), new BigDecimal("90.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);
        seedClaim(m2, p1, s1, "paid", "USD",
                new BigDecimal("210.0000"), new BigDecimal("200.0000"), new BigDecimal("180.0000"),
                LocalDate.of(2026, 6, 3), JUN_10, JUN_10);
        seedClaim(m2, p1, s1, "paid", "USD",
                new BigDecimal("310.0000"), new BigDecimal("300.0000"), new BigDecimal("270.0000"),
                LocalDate.of(2026, 6, 5), JUN_10, JUN_10);
        seedClaim(m3, p1, s2, "paid", "USD",
                new BigDecimal("410.0000"), new BigDecimal("400.0000"), new BigDecimal("360.0000"),
                LocalDate.of(2026, 6, 6), JUN_10, JUN_10);
        seedClaim(m3, p1, s2, "paid", "USD",
                new BigDecimal("510.0000"), new BigDecimal("500.0000"), new BigDecimal("450.0000"),
                LocalDate.of(2026, 6, 8), JUN_10, JUN_10);

        JsonNode envelope = getJson("/api/v1/reports/claims/frequency-severity"
                + "?serviceFrom=2026-06-01&serviceTo=2026-06-30");
        JsonNode data = envelope.get("data");

        assertEquals("CLAIMS_FREQUENCY_SEVERITY", envelope.get("reportKey").asText());
        assertEquals(1, envelope.get("warnings").size());
        assertTrue(envelope.get("warnings").get(0).asText().contains("member_status_history"),
                "G48 exposure-proxy caveat expected in warnings");
        assertEquals(2, data.size());

        JsonNode clinical = findByScheme(data, s1);
        assertEquals("HEALTH", clinical.get("insuranceLine").asText());
        assertEquals(3, clinical.get("claimCount").asLong());
        assertDecimal("200.00", clinical.get("severityMean"));
        assertDecimal("200.00", clinical.get("severityMedian"));
        assertDecimal("1.97", clinical.get("exposureMemberMonths"));
        assertTrue(clinical.get("frequency").decimalValue().compareTo(new BigDecimal("18")) > 0,
                "annualised frequency should be positive and >18");

        JsonNode maternity = findByScheme(data, s2);
        assertEquals(2, maternity.get("claimCount").asLong());
        assertDecimal("450.00", maternity.get("severityMean"));
        assertDecimal("450.00", maternity.get("severityMedian"));
        // 1 active member × 30 days ÷ 30.4375 = 0.99 months
        assertDecimal("0.99", maternity.get("exposureMemberMonths"));
    }

    @Test
    void frequencySeverity_whenDisabled_returns403() {
        setReportDisabled("CLAIMS_FREQUENCY_SEVERITY");
        try {
            get("/api/v1/reports/claims/frequency-severity"
                    + "?serviceFrom=2026-06-01&serviceTo=2026-06-30")
                    .expectStatus().isForbidden();
        } finally {
            setReportEnabled("CLAIMS_FREQUENCY_SEVERITY");
        }
    }

    @Test
    void frequencySeverity_export_producesWorkbookAndAuditEvent() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("110.0000"), new BigDecimal("100.0000"), new BigDecimal("90.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);

        byte[] bytes = getExcelBytes("/api/v1/reports/claims/frequency-severity/export/excel"
                + "?serviceFrom=2026-06-01&serviceTo=2026-06-30");
        assertEquals((byte) 0x50, bytes[0]);
        assertEquals((byte) 0x4B, bytes[1]);

        assertTrue(securityEventPublished("CLAIMS_FREQUENCY_SEVERITY", Duration.ofSeconds(10)),
                "export should emit a DATA_ACCESS security event");
    }

    private static JsonNode findByScheme(JsonNode rows, UUID schemeId) {
        for (JsonNode row : rows) {
            if (schemeId.toString().equals(row.get("schemeId").asText())) {
                return row;
            }
        }
        throw new AssertionError("no row for scheme " + schemeId + " in " + rows);
    }
}
