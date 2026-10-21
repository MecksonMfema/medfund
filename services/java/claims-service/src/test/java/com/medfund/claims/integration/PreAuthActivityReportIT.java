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
 * Full-app HTTP tests for the PRE_AUTH_ACTIVITY report (G43): per-status /
 * per-currency activity on the requested-date clock, the claims-side
 * R04/R05 proxy signal, window filtering, export + audit, and the 403 gate
 * (plan §16 PreAuthActivityReportIT).
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class PreAuthActivityReportIT extends AbstractClaimsReportIT {

    private static final LocalDate JUN_10 = LocalDate.of(2026, 6, 10);

    @Test
    void preAuthActivity_returnsStatusBreakdownWithRates() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);

        seedPreAuth(m1, p1, "approved", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"),
                LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 10));
        seedPreAuth(m1, p1, "approved", "USD",
                new BigDecimal("2000.0000"), new BigDecimal("1500.0000"),
                LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 11));
        seedPreAuth(m1, p1, "rejected", "USD",
                new BigDecimal("500.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 12));
        seedPreAuth(m1, p1, "pending", "USD",
                new BigDecimal("700.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 15), null);
        seedPreAuth(m1, p1, "approved", "ZMW",
                new BigDecimal("3000.0000"), new BigDecimal("2800.0000"),
                LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 25));
        seedPreAuth(m1, p1, "approved", "USD",
                new BigDecimal("9000.0000"), new BigDecimal("8000.0000"),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5));

        UUID r04 = seedClaim(m1, p1, s1, "rejected", "USD",
                new BigDecimal("900.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12), LocalDate.of(2026, 6, 12));
        UUID r05 = seedClaim(m1, p1, s1, "rejected", "USD",
                new BigDecimal("600.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 15));
        setRejectionReason(r04, "R04");
        setRejectionReason(r05, "R05");

        JsonNode envelope = getJson("/api/v1/reports/claims/pre-auth-activity"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30");
        JsonNode data = envelope.get("data");

        assertEquals("PRE_AUTH_ACTIVITY", envelope.get("reportKey").asText());
        assertEquals(4, data.get("byStatus").size());

        JsonNode approvedUsd = findByStatus(data.get("byStatus"), "APPROVED", "USD");
        assertEquals(2, approvedUsd.get("count").asLong());
        assertDecimal("3000.0000", approvedUsd.get("totalRequested"));
        assertDecimal("2300.0000", approvedUsd.get("totalApproved"));
        assertDecimal("4", approvedUsd.get("avgDecisionDays"));
        assertDecimal("50.00", approvedUsd.get("approvalRatePct"));

        JsonNode rejectedUsd = findByStatus(data.get("byStatus"), "REJECTED", "USD");
        assertEquals(1, rejectedUsd.get("count").asLong());
        assertDecimal("500.0000", rejectedUsd.get("totalRequested"));
        assertDecimal("2", rejectedUsd.get("avgDecisionDays"));

        JsonNode pendingUsd = findByStatus(data.get("byStatus"), "PENDING", "USD");
        assertEquals(1, pendingUsd.get("count").asLong());
        assertTrue(pendingUsd.get("avgDecisionDays").isNull(), "pending has no decision date");

        JsonNode approvedZmw = findByStatus(data.get("byStatus"), "APPROVED", "ZMW");
        assertEquals(1, approvedZmw.get("count").asLong());
        assertDecimal("100.00", approvedZmw.get("approvalRatePct"));

        JsonNode signal = data.get("r04r05Signal");
        assertEquals(1, signal.get("r04Count").asLong());
        assertEquals(1, signal.get("r05Count").asLong());
        assertDecimal("1500.0000", signal.get("totalClaimedInR04R05"));
    }

    @Test
    void preAuthActivity_whenDisabled_returns403() {
        get("/api/v1/reports/claims/pre-auth-activity"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30").expectStatus().isOk();

        setReportDisabled("PRE_AUTH_ACTIVITY");
        try {
            get("/api/v1/reports/claims/pre-auth-activity"
                    + "?periodStart=2026-06-01&periodEnd=2026-06-30")
                    .expectStatus().isForbidden();
        } finally {
            setReportEnabled("PRE_AUTH_ACTIVITY");
        }
    }

    @Test
    void preAuthActivity_export_producesWorkbookAndAuditEvent() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        seedPreAuth(m1, p1, "approved", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"),
                LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 10));

        byte[] bytes = getExcelBytes("/api/v1/reports/claims/pre-auth-activity/export/excel"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30");
        assertEquals((byte) 0x50, bytes[0]);
        assertEquals((byte) 0x4B, bytes[1]);

        assertTrue(securityEventPublished("PRE_AUTH_ACTIVITY", Duration.ofSeconds(10)),
                "export should emit a DATA_ACCESS security event");
    }

    private static JsonNode findByStatus(JsonNode rows, String status, String currency) {
        for (JsonNode row : rows) {
            if (status.equals(row.get("status").asText())
                    && currency.equals(row.get("currencyCode").asText())) {
                return row;
            }
        }
        throw new AssertionError("no " + status + "/" + currency + " row in " + rows);
    }
}
