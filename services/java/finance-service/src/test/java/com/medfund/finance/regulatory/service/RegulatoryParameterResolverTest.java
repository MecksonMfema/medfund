package com.medfund.finance.regulatory.service;

import com.medfund.rules.fact.RegulatoryParameterFact;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.report.regulatory.RegulatoryDefaultsLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level assertions for the Phase-15 rules-engine escape hatch. Pins the
 * three-step resolution order:
 *
 * <ol>
 *   <li>Rules-engine tenant override wins over YAML defaults.</li>
 *   <li>YAML falls back when no rule fires (or a rule sets a null value).</li>
 *   <li>Fail-loud {@link RegulatoryParameterMissingException} when both empty
 *       — never a silent zero on a live report.</li>
 * </ol>
 *
 * <p>Also pins the fail-open path: an engine blip drops to YAML rather than
 * blocking the whole report, and the fact carried to the rules-engine has the
 * lookup key + jurisdiction + effective-date set so a rule condition can
 * filter on any of them.
 */
class RegulatoryParameterResolverTest {

    private static final String IPEC = "ZW_IPEC_SHORT_TERM";
    private static final String KEY_RATIO = "min_solvency_ratio";
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 6, 30);
    private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private TenantRuleLoader tenantRuleLoader;
    private RuleEvaluationService ruleEvaluationService;
    private RegulatoryDefaultsLoader defaultsLoader;
    private RegulatoryParameterResolver resolver;

    @BeforeEach
    void setUp() {
        tenantRuleLoader = mock(TenantRuleLoader.class);
        ruleEvaluationService = mock(RuleEvaluationService.class);
        defaultsLoader = mock(RegulatoryDefaultsLoader.class);
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        resolver = new RegulatoryParameterResolver(
                tenantRuleLoader, ruleEvaluationService, defaultsLoader);
    }

    @Test
    void resolve_rulesEngineOverride_winsOverYaml() {
        // Rule mutates the fact with a value; YAML default would return 1.30 but
        // the rule fires 1.45. The rule-engine value is returned and YAML is
        // never consulted.
        AtomicReference<RegulatoryParameterFact> captured = new AtomicReference<>();
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT.toString()), eq("REGULATORY_PARAMETER"), any()))
                .thenAnswer(invocation -> {
                    RegulatoryParameterFact fact = (RegulatoryParameterFact) invocation.getArgument(2);
                    captured.set(fact);
                    fact.setParameterValue(new BigDecimal("1.45"), "IPEC uplift 2027-Q1");
                    return Mono.just(List.of());
                });

        StepVerifier.create(resolver.resolve(TENANT, IPEC, KEY_RATIO, EFFECTIVE))
                .assertNext(value -> assertThat(value).isEqualByComparingTo("1.45"))
                .verifyComplete();

        RegulatoryParameterFact fact = captured.get();
        assertThat(fact.getParameterKey()).isEqualTo(KEY_RATIO);
        assertThat(fact.getJurisdiction()).isEqualTo(IPEC);
        assertThat(fact.getEffectiveFrom()).isEqualTo(EFFECTIVE);
        verify(defaultsLoader, never()).lookup(anyString(), anyString(), any(LocalDate.class));
    }

    @Test
    void resolve_noRuleFires_fallsBackToYaml() {
        // Rule doesn't mutate — YAML default 1.30 is used.
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("REGULATORY_PARAMETER"), any()))
                .thenReturn(Mono.just(List.of()));
        when(defaultsLoader.lookup(IPEC, KEY_RATIO, EFFECTIVE))
                .thenReturn(Optional.of(new BigDecimal("1.30")));

        StepVerifier.create(resolver.resolve(TENANT, IPEC, KEY_RATIO, EFFECTIVE))
                .assertNext(value -> assertThat(value).isEqualByComparingTo("1.30"))
                .verifyComplete();
    }

    @Test
    void resolve_ruleFiresWithNullValue_fallsBackToYaml() {
        // Emitter-side typo — rule fires but yields null; caller must fall back
        // to YAML rather than treat null as "override to null".
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("REGULATORY_PARAMETER"), any()))
                .thenAnswer(invocation -> {
                    RegulatoryParameterFact fact = (RegulatoryParameterFact) invocation.getArgument(2);
                    fact.setParameterValue(null, "Rule with typo");
                    return Mono.just(List.of());
                });
        when(defaultsLoader.lookup(IPEC, KEY_RATIO, EFFECTIVE))
                .thenReturn(Optional.of(new BigDecimal("1.30")));

        StepVerifier.create(resolver.resolve(TENANT, IPEC, KEY_RATIO, EFFECTIVE))
                .assertNext(value -> assertThat(value).isEqualByComparingTo("1.30"))
                .verifyComplete();
    }

    @Test
    void resolve_engineFailure_fallsBackToYaml() {
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("REGULATORY_PARAMETER"), any()))
                .thenReturn(Mono.error(new RuntimeException("KieContainer exploded")));
        when(defaultsLoader.lookup(IPEC, KEY_RATIO, EFFECTIVE))
                .thenReturn(Optional.of(new BigDecimal("1.30")));

        StepVerifier.create(resolver.resolve(TENANT, IPEC, KEY_RATIO, EFFECTIVE))
                .assertNext(value -> assertThat(value).isEqualByComparingTo("1.30"))
                .verifyComplete();
    }

    @Test
    void resolve_ruleEmptyAndYamlEmpty_failLoud() {
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("REGULATORY_PARAMETER"), any()))
                .thenReturn(Mono.just(List.of()));
        when(defaultsLoader.lookup(IPEC, KEY_RATIO, EFFECTIVE))
                .thenReturn(Optional.empty());

        StepVerifier.create(resolver.resolve(TENANT, IPEC, KEY_RATIO, EFFECTIVE))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(RegulatoryParameterMissingException.class);
                    assertThat(err.getMessage())
                            .contains(KEY_RATIO)
                            .contains(IPEC)
                            .contains(EFFECTIVE.toString())
                            .contains("regulatory-defaults");
                })
                .verify();
    }

    @Test
    void resolve_nullTenant_errorsImmediately() {
        StepVerifier.create(resolver.resolve(null, IPEC, KEY_RATIO, EFFECTIVE))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void resolve_blankJurisdiction_errorsImmediately() {
        StepVerifier.create(resolver.resolve(TENANT, "", KEY_RATIO, EFFECTIVE))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(resolver.resolve(TENANT, null, KEY_RATIO, EFFECTIVE))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void resolve_blankParameterKey_errorsImmediately() {
        StepVerifier.create(resolver.resolve(TENANT, IPEC, "", EFFECTIVE))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(resolver.resolve(TENANT, IPEC, null, EFFECTIVE))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void resolve_tenantIsolation_ensureLoadedGetsTenantId() {
        // bug_rules_engine_tenant_isolation guard — the loader receives the
        // caller's tenantId; the engine ships one KieContainer per tenant so
        // an override for tenant A never fires for tenant B.
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("REGULATORY_PARAMETER"), any()))
                .thenReturn(Mono.just(List.of()));
        when(defaultsLoader.lookup(IPEC, KEY_RATIO, EFFECTIVE))
                .thenReturn(Optional.of(new BigDecimal("1.30")));

        StepVerifier.create(resolver.resolve(TENANT, IPEC, KEY_RATIO, EFFECTIVE))
                .assertNext(value -> assertThat(value).isEqualByComparingTo("1.30"))
                .verifyComplete();

        verify(tenantRuleLoader).ensureLoaded(TENANT);
    }
}
