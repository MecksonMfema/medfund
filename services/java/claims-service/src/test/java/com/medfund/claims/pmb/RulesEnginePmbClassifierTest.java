package com.medfund.claims.pmb;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.repository.ClaimLineRepository;
import com.medfund.rules.fact.PmbClassificationFact;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RulesEnginePmbClassifierTest {

    private static final String TENANT = "tenant-A";

    private RuleEvaluationService rules;
    private ClaimLineRepository lines;
    private RulesEnginePmbClassifier classifier;

    @BeforeEach
    void setUp() {
        rules = mock(RuleEvaluationService.class);
        lines = mock(ClaimLineRepository.class);
        classifier = new RulesEnginePmbClassifier(rules, lines);
    }

    // ── Tenant context ──────────────────────────────────────────────────────

    @Test
    void classify_noTenantOnContext_returnsNotPmb_withoutHittingEngine() {
        Claim c = claim("[\"A15.0\"]", null);

        StepVerifier.create(classifier.classify(c))
                .assertNext(v -> assertThat(v.isPmb()).isFalse())
                .verifyComplete();

        verify(rules, never()).evaluateInGroup(anyString(), anyString(), any());
    }

    @Test
    void classify_blankTenantContext_returnsNotPmb() {
        Claim c = claim("[\"A15.0\"]", null);

        StepVerifier.create(withTenant(classifier.classify(c), "   "))
                .assertNext(v -> assertThat(v.isPmb()).isFalse())
                .verifyComplete();

        verify(rules, never()).evaluateInGroup(anyString(), anyString(), any());
    }

    // ── Diagnosis-only ──────────────────────────────────────────────────────

    @Test
    void classify_singleDiagnosisMatch_returnsPmbWithCode() {
        Claim c = claim("[\"A15.0\"]", null);
        when(lines.findByClaimId(any())).thenReturn(Flux.empty());
        stubRulesToMatch("A15.0", null, "PMB-001", "Pulmonary TB");

        StepVerifier.create(withTenant(classifier.classify(c), TENANT))
                .assertNext(v -> {
                    assertThat(v.isPmb()).isTrue();
                    assertThat(v.conditionCode()).isEqualTo("PMB-001");
                })
                .verifyComplete();
    }

    @Test
    void classify_emptyDiagnosisList_returnsNotPmb_withoutHittingEngine() {
        Claim c = claim("[]", null);

        StepVerifier.create(withTenant(classifier.classify(c), TENANT))
                .assertNext(v -> assertThat(v.isPmb()).isFalse())
                .verifyComplete();

        verify(rules, never()).evaluateInGroup(anyString(), anyString(), any());
    }

    @Test
    void classify_multipleDiagnoses_shortCircuitsOnFirstMatch() {
        Claim c = claim("[\"Z00.0\",\"A15.0\",\"J45.0\"]", null);
        when(lines.findByClaimId(any())).thenReturn(Flux.empty());
        // A15.0 matches PMB-001; Z00.0 doesn't match; J45.0 would match PMB-042 but shouldn't be probed.
        when(rules.evaluateInGroup(eq(TENANT), eq("PMB_CLASSIFICATION"), any()))
                .thenAnswer(inv -> {
                    PmbClassificationFact fact = inv.getArgument(2);
                    if ("A15.0".equals(fact.getDiagnosisCode())) {
                        fact.setPmbClassification("PMB-001", "Rule A");
                    }
                    return Mono.just(List.<RuleResult>of());
                });

        StepVerifier.create(withTenant(classifier.classify(c), TENANT))
                .assertNext(v -> {
                    assertThat(v.isPmb()).isTrue();
                    assertThat(v.conditionCode()).isEqualTo("PMB-001");
                })
                .verifyComplete();

        // Z00.0 probed, A15.0 probed and matched — J45.0 must NOT be probed.
        ArgumentCaptor<Object> factCap = ArgumentCaptor.forClass(Object.class);
        verify(rules, times(2)).evaluateInGroup(eq(TENANT), eq("PMB_CLASSIFICATION"), factCap.capture());
        assertThat(factCap.getAllValues())
                .allMatch(o -> o instanceof PmbClassificationFact)
                .extracting(o -> ((PmbClassificationFact) o).getDiagnosisCode())
                .containsExactly("Z00.0", "A15.0");
    }

    // ── Diagnosis + procedure ───────────────────────────────────────────────

    @Test
    void classify_procedureCodesFromClaimLines_probedAgainstEveryDiagnosis() {
        Claim c = claim("[\"N18.6\"]", null);
        c.setId(UUID.randomUUID());
        when(lines.findByClaimId(c.getId())).thenReturn(Flux.just(line("HD-CENTRE"), line("HD-HOME")));
        stubRulesToMatch("N18.6", "HD-CENTRE", "PMB-070", "Dialysis in-centre");

        StepVerifier.create(withTenant(classifier.classify(c), TENANT))
                .assertNext(v -> {
                    assertThat(v.isPmb()).isTrue();
                    assertThat(v.conditionCode()).isEqualTo("PMB-070");
                })
                .verifyComplete();

        // Two probes: (N18.6, HD-CENTRE), (N18.6, HD-HOME); first one matches so second may still be probed.
        verify(rules, atLeastOnce()).evaluateInGroup(eq(TENANT), eq("PMB_CLASSIFICATION"), any());
    }

    @Test
    void classify_procedureCodesFromClaimJsonAndLines_dedupedAndUnioned() {
        Claim c = claim("[\"A15.0\"]", "[\"CT-CHEST\"]");
        c.setId(UUID.randomUUID());
        when(lines.findByClaimId(c.getId())).thenReturn(Flux.just(line("CT-CHEST"), line("XRAY")));
        when(rules.evaluateInGroup(anyString(), anyString(), any())).thenReturn(Mono.just(List.of()));

        StepVerifier.create(withTenant(classifier.classify(c), TENANT))
                .assertNext(v -> assertThat(v.isPmb()).isFalse())
                .verifyComplete();

        ArgumentCaptor<Object> factCap = ArgumentCaptor.forClass(Object.class);
        verify(rules, atLeastOnce()).evaluateInGroup(anyString(), anyString(), factCap.capture());
        assertThat(factCap.getAllValues())
                .extracting(o -> ((PmbClassificationFact) o).getProcedureCode())
                // CT-CHEST from both sources deduped; XRAY only from line.
                .containsExactlyInAnyOrder("CT-CHEST", "XRAY");
    }

    // ── Error tolerance ─────────────────────────────────────────────────────

    @Test
    void classify_ruleEngineError_returnsNotPmb_forThatProbeAndKeepsProbing() {
        Claim c = claim("[\"BOOM\",\"A15.0\"]", null);
        when(lines.findByClaimId(any())).thenReturn(Flux.empty());
        when(rules.evaluateInGroup(anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    PmbClassificationFact fact = inv.getArgument(2);
                    if ("BOOM".equals(fact.getDiagnosisCode())) {
                        return Mono.error(new RuntimeException("engine boom"));
                    }
                    fact.setPmbClassification("PMB-001", "Rule A");
                    return Mono.just(List.<RuleResult>of());
                });

        StepVerifier.create(withTenant(classifier.classify(c), TENANT))
                .assertNext(v -> {
                    assertThat(v.isPmb()).isTrue();
                    assertThat(v.conditionCode()).isEqualTo("PMB-001");
                })
                .verifyComplete();
    }

    @Test
    void classify_malformedJsonArray_returnsNotPmb_withoutProbing() {
        // A payload that starts with '[' but isn't a valid JSON array falls
        // through the JSON branch's exception handler → empty list → short-circuit.
        Claim c = claim("[not,valid,json", null);

        StepVerifier.create(withTenant(classifier.classify(c), TENANT))
                .assertNext(v -> assertThat(v.isPmb()).isFalse())
                .verifyComplete();

        verify(rules, never()).evaluateInGroup(anyString(), anyString(), any());
    }

    @Test
    void parseCodes_supportsLegacyCommaDelimited() {
        assertThat(RulesEnginePmbClassifier.parseCodes("A15.0, N18.6 , ,J45.0"))
                .containsExactly("A15.0", "N18.6", "J45.0");
    }

    @Test
    void parseCodes_nullOrBlank_returnsEmpty() {
        assertThat(RulesEnginePmbClassifier.parseCodes(null)).isEmpty();
        assertThat(RulesEnginePmbClassifier.parseCodes("   ")).isEmpty();
        assertThat(RulesEnginePmbClassifier.parseCodes("[]")).isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubRulesToMatch(String targetDiag, String targetProc, String code, String note) {
        when(rules.evaluateInGroup(eq(TENANT), eq("PMB_CLASSIFICATION"), any()))
                .thenAnswer(inv -> {
                    PmbClassificationFact fact = inv.getArgument(2);
                    boolean diagMatch = targetDiag == null || targetDiag.equals(fact.getDiagnosisCode());
                    boolean procMatch = targetProc == null || targetProc.equals(fact.getProcedureCode());
                    if (diagMatch && procMatch) {
                        fact.setPmbClassification(code, note);
                    }
                    return Mono.just(List.<RuleResult>of());
                });
    }

    private static Claim claim(String diagnosisCodes, String procedureCodes) {
        Claim c = new Claim();
        c.setId(UUID.randomUUID());
        c.setClaimNumber("CLM-1");
        c.setDiagnosisCodes(diagnosisCodes);
        c.setProcedureCodes(procedureCodes);
        return c;
    }

    private static ClaimLine line(String tariffCode) {
        ClaimLine l = new ClaimLine();
        l.setId(UUID.randomUUID());
        l.setTariffCode(tariffCode);
        l.setQuantity(1);
        l.setClaimedAmount(BigDecimal.ONE);
        return l;
    }

    private static <T> Mono<T> withTenant(Mono<T> mono, String tenant) {
        return mono.contextWrite(Context.of(TenantContext.KEY, tenant));
    }
}
