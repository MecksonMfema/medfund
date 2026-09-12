package com.medfund.claims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the per-holder claims aggregate — the endpoint must return one row
 * per corporate group (holderType=GROUP) plus one row per individual
 * policyholder (holderType=INDIVIDUAL, member with no group), not collapse
 * all ungrouped members into a single "Ungrouped" bucket.
 *
 * <p>Regression test for the 2026-09 per-holder rewrite of
 * {@link com.medfund.claims.repository.ClaimsReportQueryRepository#perGroupSummary}.
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class PerHolderClaimsReportIT extends AbstractClaimsReportIT {

    @Test
    void perGroupSummary_returnsGroupAndIndividualRows() {
        UUID scheme   = seedScheme("Bronze");
        UUID provider = seedProvider("Clinic A");
        UUID group    = seedGroup("Acme Corp");

        // Grouped member — should collapse under Acme Corp's GROUP row
        UUID grouped1 = seedMember("Alice", "Zulu",  group, scheme);
        UUID grouped2 = seedMember("Bob",   "Young", group, scheme);

        // Ungrouped members — each must appear as its own INDIVIDUAL row.
        // seedMember requires a non-null group_id (bind() enforces it), so
        // insert the two ungrouped members with the group_id column
        // explicitly NULL via a direct SQL insert.
        UUID solo1 = seedUngroupedMember("Carol", "Xu",   scheme);
        UUID solo2 = seedUngroupedMember("David", "Wong", scheme);

        // Two claims per member so the funnel amounts don't accidentally
        // match the (1 claim × 100) shape.
        LocalDate adjudicated = LocalDate.of(2026, 7, 15);
        for (UUID m : new UUID[] { grouped1, grouped2, solo1, solo2 }) {
            seedClaim(m, provider, scheme, "paid", "USD",
                    new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("70.00"),
                    LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2), adjudicated);
            seedClaim(m, provider, scheme, "paid", "USD",
                    new BigDecimal("50.00"), new BigDecimal("50.00"), new BigDecimal("50.00"),
                    LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 6), adjudicated);
        }

        JsonNode envelope = getJson(
                "/api/v1/reports/claims/groups?periodStart=2026-07-01&periodEnd=2026-07-31");

        JsonNode rows = envelope.get("data");
        assertNotNull(rows, "envelope.data must be present");
        assertEquals(3, rows.size(),
                "expected 1 GROUP row (Acme) + 2 INDIVIDUAL rows (Carol, David) but got " + rows.size()
                        + " — payload was " + rows);

        JsonNode groupRow      = findByName(rows, "Acme Corp");
        JsonNode individualRow = findByName(rows, "Carol Xu");
        JsonNode individual2   = findByName(rows, "David Wong");

        assertNotNull(groupRow,      "missing GROUP row for Acme Corp");
        assertNotNull(individualRow, "missing INDIVIDUAL row for Carol Xu");
        assertNotNull(individual2,   "missing INDIVIDUAL row for David Wong");

        assertEquals("GROUP",      groupRow.path("holderType").asText());
        assertEquals("INDIVIDUAL", individualRow.path("holderType").asText());
        assertEquals("INDIVIDUAL", individual2.path("holderType").asText());

        // Sanity — Acme's GROUP row must aggregate both grouped members'
        // 4 claims combined, not just one. 2 members × 2 claims = 4.
        assertEquals(4, groupRow.path("claimCount").asLong(),
                "Acme GROUP row must aggregate all four claims from its two members");
        // Each individual carries their own 2 claims.
        assertEquals(2, individualRow.path("claimCount").asLong());
        assertEquals(2, individual2.path("claimCount").asLong());

        // Book-wide totals in the envelope must include individuals (regression
        // guard against a filter like `WHERE group_id IS NOT NULL`).
        JsonNode perCurrency = envelope.path("perCurrency").path("USD");
        assertTrue(perCurrency.has("totalAmount"),
                "envelope.perCurrency.USD.totalAmount must be present");
    }

    private UUID seedUngroupedMember(String first, String last, UUID schemeId) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO members (id, first_name, last_name, member_number, group_id, scheme_id, status)
                VALUES (:id, :first, :last, :mn, NULL, :sid, 'ACTIVE')
                """)
                .bind("id", id).bind("first", first).bind("last", last)
                .bind("mn", "M" + id.toString().substring(0, 8).toUpperCase())
                .bind("sid", schemeId)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
        return id;
    }

    private static JsonNode findByName(JsonNode rows, String name) {
        for (JsonNode r : rows) {
            if (name.equals(r.path("dimensionName").asText())) return r;
        }
        return null;
    }
}
