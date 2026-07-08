package com.medfund.claims.service;

import com.medfund.claims.entity.Claim;
import com.medfund.rules.fact.ClaimFact;
import com.medfund.rules.fact.MemberFact;
import com.medfund.rules.fact.ProviderFact;
import com.medfund.rules.model.ProrationStrategy;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import io.r2dbc.spi.Readable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Priority-resolution tests for {@link ProrationService}. Strategy arithmetic
 * is covered by {@code ProrationStrategyTest} in rules-engine — this suite
 * asserts which strategy wins in which scenario, not the numbers themselves.
 *
 * <p>Mocks the fluent {@link DatabaseClient} chain with a fake {@link Readable}
 * so the service's real mapper lambdas execute against seeded row data (rather
 * than pre-computing the mapped result in the test and short-circuiting the
 * mapper). This exercises the DB-to-domain translation code paths.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProrationServiceTest {

    @Mock private DatabaseClient databaseClient;
    @Mock private ClaimFactBuilder factBuilder;
    @Mock private RuleEvaluationService ruleEvaluationService;
    @Mock private TenantRuleLoader tenantRuleLoader;

    private ProrationService service;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID BENEFIT = UUID.randomUUID();
    private static final UUID PREV_SCHEME = UUID.randomUUID();
    private static final UUID NEW_SCHEME = UUID.randomUUID();
    private static final UUID PREV_BENEFIT = UUID.randomUUID();

    private QueryStub queryStub;

    @BeforeEach
    void setUp() {
        service = new ProrationService(databaseClient, factBuilder,
                ruleEvaluationService, tenantRuleLoader);

        queryStub = new QueryStub();
        queryStub.install(databaseClient);

        // Default fact + rule mocks — no rule fires unless a test opts in.
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(factBuilder.build(any())).thenReturn(Mono.just(
                new ClaimFactBuilder.Facts(new ClaimFact(), new MemberFact(), new ProviderFact())));
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("BENEFIT_PRORATION"),
                any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of()));
    }

    private Claim newClaim() {
        Claim c = new Claim();
        c.setId(UUID.randomUUID());
        c.setMemberId(MEMBER);
        c.setBenefitId(BENEFIT);
        c.setSchemeId(NEW_SCHEME);
        c.setServiceDate(LocalDate.of(2026, 7, 1));
        c.setClaimedAmount(new BigDecimal("100"));
        return c;
    }

    // ── Scenario: no scheme change on file → skips proration ─────────────────

    @Test
    void noSchemeChange_returnsNoneWithRawLimit_andPreExistingConsumed() {
        // scheme_changes lookup returns empty; sumConsumed (fresh member) returns 200.
        queryStub.emptyRow("FROM scheme_changes");
        queryStub.row("SUM(approved_amount)", Map.of("used", new BigDecimal("200")));

        StepVerifier.create(service.resolveEffectiveLimit(TENANT, newClaim(),
                new BigDecimal("1000"), "Consultation", "USD"))
                .assertNext(d -> {
                    assertThat(d.strategy()).isEqualTo(ProrationStrategy.NONE.name());
                    assertThat(d.effectiveLimit()).isEqualByComparingTo("1000");
                    assertThat(d.totalConsumed()).isEqualByComparingTo("200");
                    assertThat(d.note()).contains("No scheme change");
                })
                .verifyComplete();
    }

    // ── Scenario: cross-currency scheme change → NONE + warning ──────────────

    @Test
    void crossCurrencyChange_returnsNoneWithCrossCurrencyNote() {
        seedSchemeChange("UPGRADE");
        seedPrevBenefit(new BigDecimal("500"), "ZWL"); // old currency ≠ new currency
        // consumed sums for both schemes = 0
        queryStub.row("SUM(approved_amount)", Map.of("used", BigDecimal.ZERO));

        StepVerifier.create(service.resolveEffectiveLimit(TENANT, newClaim(),
                new BigDecimal("2000"), "Consultation", "USD"))
                .assertNext(d -> {
                    assertThat(d.strategy()).isEqualTo(ProrationStrategy.NONE.name());
                    assertThat(d.effectiveLimit()).isEqualByComparingTo("2000");
                    assertThat(d.note()).contains("Cross-currency");
                })
                .verifyComplete();
    }

    // ── Scenario: no config + no rule → falls through to NONE ────────────────

    @Test
    void noConfigNoRule_fallsThroughToPlatformDefaultNone() {
        seedSchemeChange("UPGRADE");
        seedPrevBenefit(new BigDecimal("1000"), "USD");
        queryStub.row("SUM(approved_amount)", Map.of("used", BigDecimal.ZERO));
        queryStub.emptyRow("tenant_proration_config");

        StepVerifier.create(service.resolveEffectiveLimit(TENANT, newClaim(),
                new BigDecimal("3000"), "Consultation", "USD"))
                .assertNext(d -> {
                    assertThat(d.strategy()).isEqualTo(ProrationStrategy.NONE.name());
                    assertThat(d.effectiveLimit()).isEqualByComparingTo("3000");
                    assertThat(d.note()).contains("No config or rule");
                })
                .verifyComplete();
    }

    // ── Scenario: tenant config sets DELTA_CREDIT → arithmetic applied ───────

    @Test
    void tenantConfigDeltaCredit_appliesStrategyAgainstTotalConsumed() {
        seedSchemeChange("UPGRADE");
        seedPrevBenefit(new BigDecimal("1000"), "USD");
        // consumed on both schemes = 800 total (sumConsumed fires 3× — matches all)
        queryStub.row("SUM(approved_amount)", Map.of("used", new BigDecimal("400")));
        queryStub.row("tenant_proration_config", nullSafeMap("default_strategy", "DELTA_CREDIT"));

        // Total consumed = 400 (prev) + 400 (new) = 800  → effective = 3000 − 800 = 2200
        StepVerifier.create(service.resolveEffectiveLimit(TENANT, newClaim(),
                new BigDecimal("3000"), "Consultation", "USD"))
                .assertNext(d -> {
                    assertThat(d.strategy()).isEqualTo(ProrationStrategy.DELTA_CREDIT.name());
                    assertThat(d.effectiveLimit()).isEqualByComparingTo("2200");
                    assertThat(d.totalConsumed()).isEqualByComparingTo("800");
                    assertThat(d.note()).contains("Tenant-config strategy DELTA_CREDIT");
                })
                .verifyComplete();
    }

    // ── Scenario: HYBRID_BY_DIRECTION picks upgrade strategy ─────────────────

    @Test
    void hybridByDirection_routesUpgradeToDirectionalStrategy() {
        seedSchemeChange("UPGRADE");
        seedPrevBenefit(new BigDecimal("1000"), "USD");
        queryStub.row("SUM(approved_amount)", Map.of("used", BigDecimal.ZERO));
        queryStub.row("tenant_proration_config", nullSafeMap(
                "default_strategy", "HYBRID_BY_DIRECTION",
                "upgrade_strategy", "DELTA_CREDIT",
                "downgrade_strategy", "NONE",
                "currency_strategy", "NONE"));

        StepVerifier.create(service.resolveEffectiveLimit(TENANT, newClaim(),
                new BigDecimal("3000"), "Consultation", "USD"))
                .assertNext(d -> {
                    assertThat(d.strategy()).isEqualTo(ProrationStrategy.DELTA_CREDIT.name());
                    // Nothing consumed yet → effective = 3000 − 0
                    assertThat(d.effectiveLimit()).isEqualByComparingTo("3000");
                })
                .verifyComplete();
    }

    // ── Scenario: rule wins over tenant config ───────────────────────────────

    @Test
    void ruleSetStrategy_winsOverTenantConfig() {
        seedSchemeChange("UPGRADE");
        seedPrevBenefit(new BigDecimal("1000"), "USD");
        queryStub.row("SUM(approved_amount)", Map.of("used", BigDecimal.ZERO));
        queryStub.row("tenant_proration_config", nullSafeMap("default_strategy", "CALENDAR"));

        // Rule fires and sets ClaimFact.prorationStrategy = "NONE"
        ClaimFact ruleFact = new ClaimFact();
        ruleFact.setProrationStrategy("NONE");
        when(factBuilder.build(any())).thenReturn(Mono.just(
                new ClaimFactBuilder.Facts(ruleFact, new MemberFact(), new ProviderFact())));

        StepVerifier.create(service.resolveEffectiveLimit(TENANT, newClaim(),
                new BigDecimal("3000"), "Consultation", "USD"))
                .assertNext(d -> {
                    assertThat(d.strategy()).isEqualTo(ProrationStrategy.NONE.name());
                    // NONE → effective = raw newAnnualLimit
                    assertThat(d.effectiveLimit()).isEqualByComparingTo("3000");
                    assertThat(d.note()).contains("Rule-selected strategy NONE");
                })
                .verifyComplete();
    }

    // ── Scenario: rule sets an unknown strategy → falls through to config ────

    @Test
    void ruleSetInvalidStrategy_ignoredAndFallsThroughToConfig() {
        seedSchemeChange("UPGRADE");
        seedPrevBenefit(new BigDecimal("1000"), "USD");
        queryStub.row("SUM(approved_amount)", Map.of("used", BigDecimal.ZERO));
        queryStub.row("tenant_proration_config", nullSafeMap("default_strategy", "DELTA_CREDIT"));

        ClaimFact ruleFact = new ClaimFact();
        ruleFact.setProrationStrategy("MADE_UP_STRATEGY"); // not in the enum
        when(factBuilder.build(any())).thenReturn(Mono.just(
                new ClaimFactBuilder.Facts(ruleFact, new MemberFact(), new ProviderFact())));

        StepVerifier.create(service.resolveEffectiveLimit(TENANT, newClaim(),
                new BigDecimal("3000"), "Consultation", "USD"))
                .assertNext(d -> {
                    // Invalid rule value ignored → tenant config wins
                    assertThat(d.strategy()).isEqualTo(ProrationStrategy.DELTA_CREDIT.name());
                    assertThat(d.note()).contains("Tenant-config strategy DELTA_CREDIT");
                })
                .verifyComplete();
    }

    // ── Scenario: prev-scheme benefit missing (renamed / added new) ──────────

    @Test
    void prevSchemeBenefitNotFound_carriesNullPrevFieldsButStillComputes() {
        seedSchemeChange("UPGRADE");
        queryStub.emptyRow("FROM scheme_benefits"); // no matching name under prev scheme
        queryStub.row("SUM(approved_amount)", Map.of("used", BigDecimal.ZERO));
        queryStub.row("tenant_proration_config", nullSafeMap("default_strategy", "DELTA_CREDIT"));

        StepVerifier.create(service.resolveEffectiveLimit(TENANT, newClaim(),
                new BigDecimal("3000"), "Consultation", "USD"))
                .assertNext(d -> {
                    // prev fields null → totalConsumed only counts new-scheme sum (= 0)
                    // DELTA_CREDIT: 3000 − 0 = 3000
                    assertThat(d.strategy()).isEqualTo(ProrationStrategy.DELTA_CREDIT.name());
                    assertThat(d.effectiveLimit()).isEqualByComparingTo("3000");
                })
                .verifyComplete();
    }

    /** {@code Map.of} rejects null values — this preserves them for stubbing missing config columns. */
    private static Map<String, Object> nullSafeMap(String... keyValues) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) m.put(keyValues[i], keyValues[i + 1]);
        // Guarantee all four proration_config columns are present as keys (null-valued ones fall through in loadTenantConfig).
        for (String key : List.of("default_strategy", "upgrade_strategy", "downgrade_strategy", "currency_strategy")) {
            m.putIfAbsent(key, null);
        }
        return m;
    }

    // ── Fixture helpers ──────────────────────────────────────────────────────

    private void seedSchemeChange(String changeKind) {
        Map<String, Object> row = new HashMap<>();
        row.put("from_scheme_id", PREV_SCHEME);
        row.put("to_scheme_id", NEW_SCHEME);
        row.put("effective_date", LocalDate.of(2026, 6, 1));
        row.put("change_kind", changeKind);
        queryStub.row("FROM scheme_changes", row);
    }

    private void seedPrevBenefit(BigDecimal annualLimit, String currencyCode) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", PREV_BENEFIT);
        row.put("annual_limit", annualLimit);
        row.put("currency_code", currencyCode);
        queryStub.row("FROM scheme_benefits", row);
    }

    /**
     * SQL-fragment → row-data fixture that satisfies the fluent DatabaseClient
     * chain used by {@link ProrationService}. The service always follows the
     * shape {@code sql(…).bind(…).map(row -> …).one()} — we intercept .map()
     * to apply the real mapper against a fake {@link Readable} seeded with our
     * test data. Empty stubbing returns {@code Mono.empty()} from .one().
     */
    private static final class QueryStub {
        private final Map<String, Map<String, Object>> fragmentToRow = new HashMap<>();
        private final Map<String, Boolean> emptyFragments = new HashMap<>();

        void row(String sqlFragment, Map<String, Object> rowData) {
            fragmentToRow.put(sqlFragment, rowData);
        }

        void emptyRow(String sqlFragment) {
            emptyFragments.put(sqlFragment, true);
        }

        Map<String, Object> match(String sql) {
            // Explicit empty wins over row (test-side toggle for "no row" cases).
            for (String frag : emptyFragments.keySet()) {
                if (sql.contains(frag)) return null;
            }
            for (Map.Entry<String, Map<String, Object>> e : fragmentToRow.entrySet()) {
                if (sql.contains(e.getKey())) return e.getValue();
            }
            return null;
        }

        boolean isExplicitEmpty(String sql) {
            for (String frag : emptyFragments.keySet()) {
                if (sql.contains(frag)) return true;
            }
            return false;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void install(DatabaseClient client) {
            when(client.sql(anyString())).thenAnswer(inv -> {
                String sql = inv.getArgument(0);
                DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
                when(spec.bind(anyString(), any())).thenReturn(spec);

                when(spec.map(any(Function.class))).thenAnswer(mapInv -> {
                    Function<Readable, Object> mapper = mapInv.getArgument(0);
                    RowsFetchSpec rowsSpec = mock(RowsFetchSpec.class);

                    Map<String, Object> data = match(sql);
                    boolean forceEmpty = isExplicitEmpty(sql);

                    if (forceEmpty || data == null) {
                        when(rowsSpec.one()).thenReturn(Mono.empty());
                    } else {
                        Readable fake = fakeReadable(data);
                        Object mapped = mapper.apply(fake);
                        when(rowsSpec.one()).thenReturn(mapped != null ? Mono.just(mapped) : Mono.empty());
                    }
                    return rowsSpec;
                });

                return spec;
            });
        }

        @SuppressWarnings("unchecked")
        private static Readable fakeReadable(Map<String, Object> data) {
            Readable r = mock(Readable.class);
            // Untyped get(name) — used by asString/asDecimal helpers.
            when(r.get(anyString())).thenAnswer(inv -> data.get(inv.getArgument(0, String.class)));
            // Typed get(name, Class) — used for get("from_scheme_id", UUID.class) etc.
            when(r.get(anyString(), any(Class.class))).thenAnswer(inv -> {
                String key = inv.getArgument(0, String.class);
                return data.get(key);
            });
            return r;
        }
    }
}
