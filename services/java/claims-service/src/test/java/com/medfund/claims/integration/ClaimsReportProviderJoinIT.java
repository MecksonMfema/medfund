package com.medfund.claims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.claims.dto.ClaimsAggregateRow;
import com.medfund.claims.repository.ClaimsReportQueryRepository;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the three INNER-JOIN provider queries in
 * {@code ClaimsReportQueryRepository} — {@code perProviderSummary},
 * {@code aggregateProvider} and {@code aggregateMonthlyProvider} — which the
 * rest of the report IT suite only reaches through its scheme/member
 * dimensions.
 *
 * <p>Two things per query. First, {@code dimensionName} resolves: the join
 * onto the platform {@code public.providers} table still produces the
 * provider's name. Second, the {@code public.provider_tenants} membership
 * guard bites: claims against a provider this tenant has no membership row
 * for are absent from the aggregate, name and figures both.
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class ClaimsReportProviderJoinIT extends AbstractClaimsReportIT {

    @Autowired
    private ClaimsReportQueryRepository repository;

    private static final LocalDate JUN_01 = LocalDate.of(2026, 6, 1);
    private static final LocalDate JUN_10 = LocalDate.of(2026, 6, 10);
    private static final LocalDate JUL_05 = LocalDate.of(2026, 7, 5);
    private static final String JUNE = "?periodStart=2026-06-01&periodEnd=2026-06-30";

    @Test
    void providersReport_namesEveryLinkedProvider_andExcludesUnlinkedOnes() {
        Fixture f = seedThreeLinkedPlusOneUnlinked();

        JsonNode envelope = getJson("/api/v1/reports/claims/providers" + JUNE);

        List<String> names = names(envelope.get("data"), "dimensionName");
        assertEquals(List.of("Mutare Day Hospital", "St Mary's", "Sunrise Clinic"), names,
                "every contracted provider resolves its name through public.providers");
        assertFalse(names.contains(f.unlinkedName()),
                "a provider with no provider_tenants row for this tenant must not appear");

        List<String> ids = names(envelope.get("data"), "dimensionId");
        assertFalse(ids.contains(f.unlinked().toString()));
    }

    /**
     * Driven at the repository rather than over HTTP: the only caller of
     * {@code aggregate} today is {@code ClaimsAggregateController}, which
     * hardcodes the SCHEME dimension, so the PROVIDER branch has no URL to
     * reach it by. The tenant context the guard binds against is applied the
     * same way the seed helpers apply it.
     */
    @Test
    void aggregateProvider_namesEveryLinkedProvider_andExcludesUnlinkedOnes() {
        Fixture f = seedThreeLinkedPlusOneUnlinked();

        List<ClaimsAggregateRow> rows = repository
                .aggregate("PROVIDER", JUN_01, LocalDate.of(2026, 6, 30))
                .collectList()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertEquals(3, rows.size());
        for (ClaimsAggregateRow row : rows) {
            assertEquals("PROVIDER", row.dimension());
            assertFalse(row.dimensionName() == null || row.dimensionName().isBlank(),
                    "dimensionName comes from the providers join, never blank for a linked provider");
        }
        assertEquals(List.of("Mutare Day Hospital", "St Mary's", "Sunrise Clinic"),
                rows.stream().map(ClaimsAggregateRow::dimensionName).toList());
        assertTrue(rows.stream().noneMatch(r -> f.unlinked().equals(r.dimensionId())),
                "a provider with no provider_tenants row for this tenant must not appear");
    }

    @Test
    void aggregateMonthlyProvider_namesEveryLinkedProvider_andExcludesUnlinkedOnes() {
        Fixture f = seedThreeLinkedPlusOneUnlinked();
        // Second month for the first provider so the monthly bucketing is
        // exercised rather than collapsing to a single row per provider.
        seedClaim(f.member(), f.linked().get(0), f.scheme(), "paid", "USD",
                new BigDecimal("400.0000"), new BigDecimal("400.0000"), new BigDecimal("400.0000"),
                LocalDate.of(2026, 7, 1), JUL_05, JUL_05);

        JsonNode envelope = getJson("/api/v1/reports/aggregate/claims/monthly"
                + "?periodStart=2026-06-01&periodEnd=2026-07-31&dimension=PROVIDER");

        assertEquals(4, envelope.get("data").size(), "3 providers in June + 1 in July");
        for (JsonNode row : envelope.get("data")) {
            assertEquals("PROVIDER", row.get("dimension").asText());
            assertTrue(row.get("dimensionName").asText().length() > 0);
        }
        assertFalse(names(envelope.get("data"), "dimensionName").contains(f.unlinkedName()));

        JsonNode july = envelope.get("data").get(3);
        assertEquals("2026-07-01", july.get("month").asText());
        assertEquals("St Mary's", july.get("dimensionName").asText());
        assertDecimal("400.0000", july.get("totalAmount"));
    }

    // ── fixture ──────────────────────────────────────────────────────────

    /**
     * Three providers contracted with the IT tenant plus one that exists in
     * the platform registry with no membership row, each carrying one
     * identically-shaped June claim so any difference in the output is the
     * membership guard and nothing else.
     */
    private Fixture seedThreeLinkedPlusOneUnlinked() {
        UUID scheme = seedScheme("Clinical");
        UUID member = seedMember("Ada", "Lovelace", seedGroup("Corporate"), scheme);

        List<UUID> linked = new ArrayList<>();
        for (String name : List.of("St Mary's", "Sunrise Clinic", "Mutare Day Hospital")) {
            linked.add(seedProvider(name));
        }
        String unlinkedName = "Bulawayo Eye Centre";
        UUID unlinked = seedUnlinkedProvider(unlinkedName);

        for (UUID providerId : linked) {
            seedJuneClaim(member, providerId, scheme);
        }
        seedJuneClaim(member, unlinked, scheme);

        return new Fixture(scheme, member, linked, unlinked, unlinkedName);
    }

    private void seedJuneClaim(UUID memberId, UUID providerId, UUID schemeId) {
        seedClaim(memberId, providerId, schemeId, "paid", "USD",
                new BigDecimal("1000.0000"), new BigDecimal("900.0000"), new BigDecimal("800.0000"),
                JUN_01, JUN_10, JUN_10);
    }

    private static List<String> names(JsonNode rows, String field) {
        List<String> out = new ArrayList<>();
        rows.forEach(row -> out.add(row.get(field).asText()));
        return out;
    }

    private record Fixture(UUID scheme, UUID member, List<UUID> linked, UUID unlinked, String unlinkedName) {}
}
