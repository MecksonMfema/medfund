package com.medfund.claims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-app HTTP tests for the HIGH_COST_CLAIMANT report (G46): threshold
 * read from V132 config, fail-loud FX conversion, config-gap warning path,
 * member drill-down ledger, and the 403 gate (plan §16
 * HighCostClaimantReportIT).
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class HighCostClaimantReportIT extends AbstractClaimsReportIT {

    private static final LocalDate JUN_10 = LocalDate.of(2026, 6, 10);

    @Test
    void highCostClaimants_returnsOnlyMembersAboveThreshold() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID m2 = seedMember("Grace", "Hopper", seedGroup("Corporate"), s1);
        upsertHighCostConfig("ZMW", new BigDecimal("3000.0000"));

        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("3000.0000"), new BigDecimal("3000.0000"), new BigDecimal("3000.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);
        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("2000.0000"), new BigDecimal("2000.0000"), new BigDecimal("2000.0000"),
                LocalDate.of(2026, 6, 3), JUN_10, JUN_10);
        seedClaim(m2, p1, s1, "paid", "ZMW",
                new BigDecimal("1000.0000"), new BigDecimal("1000.0000"), new BigDecimal("1000.0000"),
                LocalDate.of(2026, 6, 5), JUN_10, JUN_10);

        JsonNode envelope = getJson("/api/v1/reports/claims/high-cost-claimants"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30&reportingCurrency=ZMW");

        assertEquals("HIGH_COST_CLAIMANT", envelope.get("reportKey").asText());
        assertEquals("ZMW", envelope.get("reportingCurrency").asText());
        assertEquals(0, envelope.get("warnings").size());
        assertEquals(1, envelope.get("data").size());
        JsonNode row = envelope.get("data").get(0);
        assertEquals(m1.toString(), row.get("memberId").asText());
        assertEquals("Ada Lovelace", row.get("memberName").asText());
        assertEquals("ZMW", row.get("currencyCode").asText());
        assertDecimal("5000.0000", row.get("cumulativePaid"));
        assertDecimal("5000.0000", row.get("cumulativePaidReporting"));
        assertEquals(2, row.get("contributingClaims").asLong());

        // perCurrency reflects the FULL window funnel (all members), not just
        // the flagged subset — G46 envelope contract. totalAmount = SUM(claimed)
        // + SUM(approved) + SUM(paid) = 6000 × 3.
        assertDecimal("18000.0000", envelope.get("perCurrency").get("ZMW").get("totalAmount"));
        assertEquals(3, envelope.get("perCurrency").get("ZMW").get("rowCount").asLong());
        assertDecimal("1.0", envelope.get("fxRates").get("ZMW"));
    }

    @Test
    void highCostClaimants_whenNoConfig_returnsEmptyWithWarning() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");
        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("5000.0000"), new BigDecimal("5000.0000"), new BigDecimal("5000.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);

        JsonNode envelope = getJson("/api/v1/reports/claims/high-cost-claimants"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30");

        assertEquals("HIGH_COST_CLAIMANT", envelope.get("reportKey").asText());
        assertEquals(0, envelope.get("data").size());
        assertTrue(envelope.get("warnings").toString().contains("High-cost threshold not configured for tenant"));
    }

    @Test
    void highCostClaimants_convertsThresholdFromConfigCurrency() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID m2 = seedMember("Grace", "Hopper", seedGroup("Corporate"), s1);
        upsertHighCostConfig("ZMW", new BigDecimal("3000.0000"));
        seedExchangeRate("ZMW", "USD", new BigDecimal("0.2500000000"), LocalDate.of(2026, 6, 15));

        // Threshold 3000 ZMW = 750 USD.
        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("2000.0000"), new BigDecimal("2000.0000"), new BigDecimal("2000.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10); // 500 USD — under
        seedClaim(m2, p1, s1, "paid", "ZMW",
                new BigDecimal("4000.0000"), new BigDecimal("4000.0000"), new BigDecimal("4000.0000"),
                LocalDate.of(2026, 6, 3), JUN_10, JUN_10); // 1000 USD — over

        JsonNode envelope = getJson("/api/v1/reports/claims/high-cost-claimants"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30&reportingCurrency=USD");

        assertEquals(1, envelope.get("data").size());
        JsonNode row = envelope.get("data").get(0);
        assertEquals(m2.toString(), row.get("memberId").asText());
        assertDecimal("4000.0000", row.get("cumulativePaid"));
        assertDecimal("1000.0000", row.get("cumulativePaidReporting"));
        assertDecimal("0.25", envelope.get("fxRates").get("ZMW"));
    }

    @Test
    void highCostClaimants_missingFxRate_failsLoudWith500() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        upsertHighCostConfig("ZMW", new BigDecimal("3000.0000"));
        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("4000.0000"), new BigDecimal("4000.0000"), new BigDecimal("4000.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);

        get("/api/v1/reports/claims/high-cost-claimants"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30&reportingCurrency=USD")
                .expectStatus().is5xxServerError();
    }

    @Test
    void highCostClaimants_whenDisabled_returns403() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");
        upsertHighCostConfig("ZMW", new BigDecimal("1000.0000"));
        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("2000.0000"), new BigDecimal("2000.0000"), new BigDecimal("2000.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);

        setReportDisabled("HIGH_COST_CLAIMANT");
        try {
            get("/api/v1/reports/claims/high-cost-claimants"
                    + "?periodStart=2026-06-01&periodEnd=2026-06-30")
                    .expectStatus().isForbidden();
        } finally {
            setReportEnabled("HIGH_COST_CLAIMANT");
        }
    }

    @Test
    void highCostClaimantDetail_returnsContributingClaimsLedger() {
        UUID s1 = seedScheme("Clinical");
        UUID p1 = seedProvider("St Mary's");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        upsertHighCostConfig("ZMW", new BigDecimal("1000.0000"));

        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("3000.0000"), new BigDecimal("3000.0000"), new BigDecimal("3000.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);
        seedClaim(m1, p1, s1, "paid", "ZMW",
                new BigDecimal("2000.0000"), new BigDecimal("2000.0000"), new BigDecimal("2000.0000"),
                LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 8));

        JsonNode envelope = getJson("/api/v1/reports/claims/high-cost-claimants/" + m1
                + "?periodStart=2026-06-01&periodEnd=2026-06-30");
        JsonNode data = envelope.get("data");
        assertEquals(2, data.get("total").asLong());
        assertEquals(2, data.get("content").size());
        assertEquals("Ada Lovelace", data.get("content").get(0).get("memberName").asText());
        // Ledger orders by adjudicated_at DESC — the 3000 claim (JUN_10) must sort
        // ahead of the 2000 claim (JUN_08); distinct dates keep the test
        // deterministic instead of relying on a UUID tiebreak.
        assertDecimal("3000.0000", data.get("content").get(0).get("paidAmount"));
    }
}
