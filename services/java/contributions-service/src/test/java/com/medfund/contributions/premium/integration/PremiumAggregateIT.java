package com.medfund.contributions.premium.integration;

import com.medfund.contributions.premium.dto.PremiumEarnedAggregateRow;
import com.medfund.contributions.premium.repository.PremiumAggregateQueryRepository;
import com.medfund.contributions.premium.repository.PremiumAggregateQueryRepository.Dimension;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Testcontainers-backed slice for the Phase 18 K8 premium-earned
 * aggregate. Seeds a multi-currency multi-line earning_schedule fixture
 * plus policy_enrichment rows (schemes + one contribution + one life
 * policy per scheme) and asserts the group-by shapes for TENANT / LINE /
 * SCHEME dimensions.
 *
 * <p>Guards applied per infra_testcontainers_pitfalls memory: JVM-lifetime
 * PostgreSQL container inherited from {@link AbstractIntegrationTest};
 * stub {@link ReactiveJwtDecoder} bean.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(PremiumAggregateIT.SecurityStub.class)
@WithTenant("11111111-1111-1111-1111-111111111111")
class PremiumAggregateIT extends AbstractIntegrationTest {

    @Autowired private PremiumAggregateQueryRepository repository;
    @Autowired private DatabaseClient db;

    private static final UUID SCHEME_GOLD   = UUID.fromString("aaaaaaa1-0000-0000-0000-000000000001");
    private static final UUID SCHEME_SILVER = UUID.fromString("aaaaaaa2-0000-0000-0000-000000000002");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 8, 1);

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test",
                            "realm_access", Map.of("roles", List.of("super_admin")))));
        }
    }

    @BeforeEach
    void seed() {
        // Wipe and reseed each test — the shared JVM container survives
        // across classes so we can't rely on Testcontainers restart.
        exec("DELETE FROM earning_schedule", Map.of());
        exec("DELETE FROM life_policies", Map.of());
        exec("DELETE FROM contributions", Map.of());
        exec("DELETE FROM schemes", Map.of());

        // Two schemes.
        exec("INSERT INTO schemes (id, name, insurance_line) VALUES (:id, 'Gold', 'HEALTH')",
                Map.of("id", SCHEME_GOLD));
        exec("INSERT INTO schemes (id, name, insurance_line) VALUES (:id, 'Silver', 'LIFE')",
                Map.of("id", SCHEME_SILVER));

        // Policy rows for the seven-way enrichment CTE: one CONTRIBUTION
        // (HEALTH, Gold scheme) and one LIFE_POLICY (LIFE, Silver scheme).
        UUID contributionId = UUID.fromString("bbbbbbb1-0000-0000-0000-000000000001");
        UUID lifePolicyId   = UUID.fromString("bbbbbbb2-0000-0000-0000-000000000002");
        exec("INSERT INTO contributions (id, member_id, scheme_id, amount) "
                        + "VALUES (:id, gen_random_uuid(), :schemeId, 100.00)",
                Map.of("id", contributionId, "schemeId", SCHEME_GOLD));
        exec("INSERT INTO life_policies (id, scheme_id) VALUES (:id, :schemeId)",
                Map.of("id", lifePolicyId, "schemeId", SCHEME_SILVER));

        // ── earning_schedule rows ────────────────────────────────────
        // Fixture: 4 rows inside the window (period_end in [Jul-1, Aug-1)):
        //   1. HEALTH, USD, contribution, earned=100
        //   2. HEALTH, ZWL, contribution, earned=45000
        //   3. LIFE,   USD, life policy,  earned=200
        //   4. LIFE,   ZWL, life policy,  earned=30000
        // Plus one row OUTSIDE the window (period_end = Aug-1 exceeds end;
        // exclusive upper bound) and one UNCLOSED row (earned_at_period_end IS NULL).
        seedEarning(contributionId, "CONTRIBUTION", "HEALTH",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15),
                new BigDecimal("100.00"), "USD");
        seedEarning(contributionId, "CONTRIBUTION", "HEALTH",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 20),
                new BigDecimal("45000.00"), "ZWL");
        seedEarning(lifePolicyId, "LIFE_POLICY", "LIFE",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 25),
                new BigDecimal("200.00"), "USD");
        seedEarning(lifePolicyId, "LIFE_POLICY", "LIFE",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                new BigDecimal("30000.00"), "ZWL");
        // Outside window: period_end = 2026-08-01 (exclusive upper bound).
        seedEarning(contributionId, "CONTRIBUTION", "HEALTH",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15),
                new BigDecimal("999.00"), "USD");
        // Unclosed row (earned_at_period_end IS NULL) — must be excluded.
        seedUnclosedEarning(contributionId, "CONTRIBUTION", "HEALTH",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 28), "USD");
    }

    private void seedEarning(UUID policyId, String policySource, String line,
                             LocalDate start, LocalDate end,
                             BigDecimal earned, String currency) {
        exec("""
                INSERT INTO earning_schedule
                    (policy_id, policy_source, insurance_line, period_start, period_end,
                     written_amount, earned_at_period_end, currency_code)
                VALUES (:p, :s, :line, :ps, :pe, :written, :earned, :c)
                """,
                Map.of("p", policyId, "s", policySource, "line", line,
                        "ps", start, "pe", end,
                        "written", earned, "earned", earned, "c", currency));
    }

    private void seedUnclosedEarning(UUID policyId, String policySource, String line,
                                     LocalDate start, LocalDate end, String currency) {
        exec("""
                INSERT INTO earning_schedule
                    (policy_id, policy_source, insurance_line, period_start, period_end,
                     written_amount, currency_code)
                VALUES (:p, :s, :line, :ps, :pe, 500.00, :c)
                """,
                Map.of("p", policyId, "s", policySource, "line", line,
                        "ps", start, "pe", end, "c", currency));
    }

    private void exec(String sql, Map<String, Object> params) {
        var spec = db.sql(sql);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        spec.fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    @Test
    void tenantDimension_groupsByCurrencyOnly() {
        List<PremiumEarnedAggregateRow> rows = repository
                .earnedPremium(PERIOD_START, PERIOD_END, Dimension.TENANT, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        // USD: 100 + 200 = 300 ; ZWL: 45000 + 30000 = 75000 (unclosed excluded, out-of-window excluded)
        assertContains(rows, r -> "USD".equals(r.currencyCode())
                && r.earnedPremium().compareTo(new BigDecimal("300.0000")) == 0);
        assertContains(rows, r -> "ZWL".equals(r.currencyCode())
                && r.earnedPremium().compareTo(new BigDecimal("75000.0000")) == 0);
        assertNoneMatch(rows, r -> r.insuranceLine() != null);
        assertNoneMatch(rows, r -> r.schemeId() != null);
    }

    @Test
    void lineDimension_addsInsuranceLine() {
        List<PremiumEarnedAggregateRow> rows = repository
                .earnedPremium(PERIOD_START, PERIOD_END, Dimension.LINE, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        // 4 rows: (HEALTH, USD, 100), (HEALTH, ZWL, 45000), (LIFE, USD, 200), (LIFE, ZWL, 30000)
        assertContains(rows, r -> "HEALTH".equals(r.insuranceLine()) && "USD".equals(r.currencyCode())
                && r.earnedPremium().compareTo(new BigDecimal("100.0000")) == 0);
        assertContains(rows, r -> "LIFE".equals(r.insuranceLine()) && "ZWL".equals(r.currencyCode())
                && r.earnedPremium().compareTo(new BigDecimal("30000.0000")) == 0);
    }

    @Test
    void lineDimension_filtersByInsuranceLine() {
        List<PremiumEarnedAggregateRow> rows = repository
                .earnedPremium(PERIOD_START, PERIOD_END, Dimension.LINE, "LIFE", null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNoneMatch(rows, r -> !"LIFE".equals(r.insuranceLine()));
        assertContains(rows, r -> "USD".equals(r.currencyCode())
                && r.earnedPremium().compareTo(new BigDecimal("200.0000")) == 0);
    }

    @Test
    void schemeDimension_resolvesSchemeIdAndName() {
        List<PremiumEarnedAggregateRow> rows = repository
                .earnedPremium(PERIOD_START, PERIOD_END, Dimension.SCHEME, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertContains(rows, r -> SCHEME_GOLD.equals(r.schemeId()) && "Gold".equals(r.schemeName())
                && "HEALTH".equals(r.insuranceLine()));
        assertContains(rows, r -> SCHEME_SILVER.equals(r.schemeId()) && "Silver".equals(r.schemeName())
                && "LIFE".equals(r.insuranceLine()));
    }

    @Test
    void schemeDimension_filtersBySchemeId() {
        List<PremiumEarnedAggregateRow> rows = repository
                .earnedPremium(PERIOD_START, PERIOD_END, Dimension.SCHEME, null, SCHEME_GOLD)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNoneMatch(rows, r -> !SCHEME_GOLD.equals(r.schemeId()));
    }

    @Test
    void emptyPeriod_returnsNoRows() {
        StepVerifier.create(repository
                        .earnedPremium(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1),
                                Dimension.TENANT, null, null)
                        .contextWrite(TenantTestContext.put())
                        .collectList())
                .assertNext(rows -> {
                    if (!rows.isEmpty()) {
                        throw new AssertionError("Expected empty result; got " + rows);
                    }
                })
                .verifyComplete();
    }

    private static <T> void assertContains(List<T> rows, java.util.function.Predicate<T> match) {
        for (T r : rows) if (match.test(r)) return;
        throw new AssertionError("No row matched predicate; rows=" + rows);
    }

    private static <T> void assertNoneMatch(List<T> rows, java.util.function.Predicate<T> match) {
        for (T r : rows) if (match.test(r)) {
            throw new AssertionError("Row unexpectedly matched: " + r);
        }
    }
}
