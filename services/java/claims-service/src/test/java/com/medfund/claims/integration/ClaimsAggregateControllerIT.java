package com.medfund.claims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Full-app HTTP tests for {@code ClaimsAggregateController} — the narrow
 * cross-service aggregate surface consumed by Phase 5 loss-ratio / Phase 8
 * cash-flow consumers. Deliberately NOT gated by {@code @RequiresReport}
 * (plan §A §3 / G50): toggling CLAIMS_SUMMARY off must not break
 * service-to-service consumers (plan §16 ClaimsAggregateControllerIT).
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class ClaimsAggregateControllerIT extends AbstractClaimsReportIT {

    private static final LocalDate JUN_10 = LocalDate.of(2026, 6, 10);

    @Test
    void aggregateClaims_returnsSchemeDimensionRows() {
        UUID s1 = seedScheme("Clinical");
        UUID s2 = seedScheme("Maternity");
        UUID p1 = seedProvider("St Mary's");
        UUID p2 = seedProvider("Sunrise Clinic");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID m2 = seedMember("Grace", "Hopper", seedGroup("SME"), s2);

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("2000.0000"), new BigDecimal("1800.0000"), new BigDecimal("1600.0000"),
                LocalDate.of(2026, 6, 3), JUN_10, JUN_10);
        seedClaim(m2, p2, s2, "paid", "ZMW",
                new BigDecimal("5000.0000"), new BigDecimal("4500.0000"), new BigDecimal("4000.0000"),
                LocalDate.of(2026, 6, 5), JUN_10, JUN_10);

        JsonNode envelope = getJson("/api/v1/reports/aggregate/claims"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30");

        assertEquals("CLAIMS_SUMMARY", envelope.get("reportKey").asText());
        assertEquals(2, envelope.get("data").size());
        assertEquals("SCHEME", envelope.get("data").get(0).get("dimension").asText());
        assertEquals("Clinical", envelope.get("data").get(0).get("dimensionName").asText());
        assertEquals("USD", envelope.get("data").get(0).get("currencyCode").asText());
        assertDecimal("3000.0000", envelope.get("data").get(0).get("totalClaimed"));
        assertDecimal("2350.0000", envelope.get("data").get(0).get("totalPaid"));

        assertEquals("ZMW", envelope.get("data").get(1).get("currencyCode").asText());
        assertDecimal("4000.0000", envelope.get("data").get(1).get("totalPaid"));

        assertDecimal("7950.0000", envelope.get("perCurrency").get("USD").get("totalAmount"));
        assertDecimal("13500.0000", envelope.get("perCurrency").get("ZMW").get("totalAmount"));
    }

    @Test
    void aggregateClaimsMonthly_returnsMonthlyPaidBuckets() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");

        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("2000.0000"), new BigDecimal("1800.0000"), new BigDecimal("1600.0000"),
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 5));

        JsonNode envelope = getJson("/api/v1/reports/aggregate/claims/monthly"
                + "?periodStart=2026-06-01&periodEnd=2026-07-31");

        assertEquals(2, envelope.get("data").size());
        assertEquals("SCHEME", envelope.get("data").get(0).get("dimension").asText());
        assertEquals("2026-06-01", envelope.get("data").get(0).get("month").asText());
        assertDecimal("750.0000", envelope.get("data").get(0).get("totalAmount"));
        assertEquals("2026-07-01", envelope.get("data").get(1).get("month").asText());
        assertDecimal("1600.0000", envelope.get("data").get(1).get("totalAmount"));
    }

    @Test
    void aggregateClaimsMonthly_respectsMemberDimension() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);

        JsonNode envelope = getJson("/api/v1/reports/aggregate/claims/monthly"
                + "?periodStart=2026-06-01&periodEnd=2026-06-30&dimension=MEMBER");
        assertEquals(1, envelope.get("data").size());
        assertEquals("MEMBER", envelope.get("data").get(0).get("dimension").asText());
        assertEquals(m1.toString(), envelope.get("data").get(0).get("dimensionId").asText());
    }

    @Test
    void aggregateClaims_worksWhenReportDisabled() {
        UUID s1 = seedScheme("Clinical");
        UUID m1 = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s1);
        UUID p1 = seedProvider("St Mary's");
        seedClaim(m1, p1, s1, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("800.0000"), new BigDecimal("750.0000"),
                LocalDate.of(2026, 6, 1), JUN_10, JUN_10);

        setReportDisabled("CLAIMS_SUMMARY");
        try {
            get("/api/v1/reports/aggregate/claims"
                    + "?periodStart=2026-06-01&periodEnd=2026-06-30")
                    .expectStatus().isOk();
        } finally {
            setReportEnabled("CLAIMS_SUMMARY");
        }
    }
}
