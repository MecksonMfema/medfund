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
 * Full-app HTTP tests for the CLAIM_STATUS_LIST pipeline-aging matrix
 * (G49): (status × age-bucket × currency) cells over the submission
 * window, the cell drill-down ledger, export + audit, and the 403 gate
 * (plan §16 ClaimStatusMatrixIT).
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class ClaimStatusMatrixIT extends AbstractClaimsReportIT {

    private static final LocalDate TODAY = LocalDate.now();

    @Test
    void statusMatrix_bucketsClaimsByAgeSinceSubmission() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("100.0000"), new BigDecimal("80.0000"), new BigDecimal("70.0000"),
                TODAY.minusDays(1), TODAY.minusDays(1), TODAY.minusDays(1));
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("200.0000"), new BigDecimal("150.0000"), new BigDecimal("120.0000"),
                TODAY.minusDays(5), TODAY.minusDays(5), TODAY.minusDays(5));
        seedClaim(m1, p1, s1, "submitted", "USD",
                new BigDecimal("300.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                TODAY.minusDays(10), TODAY.minusDays(10), TODAY.minusDays(10));
        seedClaim(m1, p1, s1, "rejected", "USD",
                new BigDecimal("400.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                TODAY.minusDays(20), TODAY.minusDays(20), TODAY.minusDays(20));
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("500.0000"), new BigDecimal("400.0000"), new BigDecimal("350.0000"),
                TODAY.minusDays(40), TODAY.minusDays(40), TODAY.minusDays(40));
        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("1000.0000"), new BigDecimal("900.0000"), new BigDecimal("800.0000"),
                TODAY.minusDays(1), TODAY.minusDays(1), TODAY.minusDays(1));

        JsonNode envelope = getJson("/api/v1/reports/claims/status-matrix"
                + "?submittedFrom=" + TODAY.minusDays(60) + "&submittedTo=" + TODAY);
        JsonNode data = envelope.get("data");

        assertEquals("CLAIM_STATUS_LIST", envelope.get("reportKey").asText());
        assertEquals(TODAY.minusDays(60).toString(), data.get("submittedFrom").asText());
        assertEquals(6, data.get("cells").size());

        JsonNode paidYoung = findCell(data.get("cells"), "PAID", "0-3", "USD");
        assertEquals(1, paidYoung.get("claimCount").asLong());
        assertDecimal("100.0000", paidYoung.get("totalClaimed"));
        assertDecimal("70.0000", paidYoung.get("totalPaid"));

        assertEquals(1, findCell(data.get("cells"), "PAID", "4-7", "USD").get("claimCount").asLong());
        assertEquals(1, findCell(data.get("cells"), "SUBMITTED", "8-14", "USD").get("claimCount").asLong());
        assertEquals(1, findCell(data.get("cells"), "REJECTED", "15-30", "USD").get("claimCount").asLong());
        JsonNode paidOld = findCell(data.get("cells"), "PAID", ">30", "USD");
        assertEquals(1, paidOld.get("claimCount").asLong());
        assertDecimal("500.0000", paidOld.get("totalClaimed"));

        JsonNode paidZmw = findCell(data.get("cells"), "PAID", "0-3", "ZMW");
        assertEquals(1, paidZmw.get("claimCount").asLong());
        assertDecimal("800.0000", paidZmw.get("totalPaid"));
    }

    @Test
    void statusMatrixDrill_returnsLedgerForOneCell() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("100.0000"), new BigDecimal("80.0000"), new BigDecimal("70.0000"),
                TODAY.minusDays(1), TODAY.minusDays(1), TODAY.minusDays(1));
        seedClaim(m1, p1, s1, "submitted", "USD",
                new BigDecimal("300.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                TODAY.minusDays(10), TODAY.minusDays(10), TODAY.minusDays(10));

        JsonNode envelope = getJson("/api/v1/reports/claims/status-matrix/drill"
                + "?submittedFrom=" + TODAY.minusDays(60) + "&submittedTo=" + TODAY
                + "&status=PAID&ageBucket=0-3");
        JsonNode data = envelope.get("data");
        assertEquals(1, data.get("total").asLong());
        assertEquals("paid", data.get("content").get(0).get("status").asText());
        assertDecimal("70.0000", data.get("content").get(0).get("paidAmount"));
    }

    @Test
    void statusMatrix_whenDisabled_returns403() {
        setReportDisabled("CLAIM_STATUS_LIST");
        try {
            get("/api/v1/reports/claims/status-matrix"
                    + "?submittedFrom=" + TODAY.minusDays(60) + "&submittedTo=" + TODAY)
                    .expectStatus().isForbidden();
        } finally {
            setReportEnabled("CLAIM_STATUS_LIST");
        }
    }

    @Test
    void statusMatrix_export_producesWorkbookAndAuditEvent() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("100.0000"), new BigDecimal("80.0000"), new BigDecimal("70.0000"),
                TODAY.minusDays(1), TODAY.minusDays(1), TODAY.minusDays(1));

        byte[] bytes = getExcelBytes("/api/v1/reports/claims/status-matrix/export/excel"
                + "?submittedFrom=" + TODAY.minusDays(60) + "&submittedTo=" + TODAY);
        assertEquals((byte) 0x50, bytes[0]);
        assertEquals((byte) 0x4B, bytes[1]);

        assertTrue(securityEventPublished("CLAIM_STATUS_LIST", Duration.ofSeconds(10)),
                "export should emit a DATA_ACCESS security event");
    }

    private static JsonNode findCell(JsonNode cells, String status, String ageBucket, String currency) {
        for (JsonNode cell : cells) {
            if (status.equals(cell.get("status").asText())
                    && ageBucket.equals(cell.get("ageBucket").asText())
                    && currency.equals(cell.get("currencyCode").asText())) {
                return cell;
            }
        }
        throw new AssertionError("no cell " + status + "/" + ageBucket + "/" + currency + " in " + cells);
    }
}
