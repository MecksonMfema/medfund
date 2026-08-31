package com.medfund.claims.pmb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.repository.ClaimLineRepository;
import com.medfund.rules.fact.PmbClassificationFact;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Real {@link PmbClassifier} — probes the tenant's {@code PMB_CLASSIFICATION}
 * rules (Phase 17 §B REG7) once per (diagnosis, procedure) pair on the claim,
 * short-circuiting on the first PMB match. Registered as a Spring
 * {@code @Component} so Phase 16's {@code @ConditionalOnMissingBean} fallback
 * ({@link NoOpPmbClassifier}) drops out automatically.
 *
 * <p>Tenant is resolved from {@link TenantContext} — the caller (adjudication
 * pipeline or {@code PmbBackfillJob}) is expected to have the reactive
 * context populated by the standard {@code TenantWebFilter} / a manual
 * {@code contextWrite}. When no tenant is on the context the classifier
 * short-circuits to {@link PmbClassification#NOT_PMB} — the same behaviour as
 * the NoOp fallback so a mis-scoped call can't accidentally mark PMB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RulesEnginePmbClassifier implements PmbClassifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    /** Sentinel used when a claim has diagnoses but no procedure lines — the
     *  DRL still needs a non-null probe value so an ICD-only rule can fire. */
    private static final String NO_PROCEDURE = "__NONE__";

    private final RuleEvaluationService ruleEvaluationService;
    private final ClaimLineRepository claimLineRepository;

    @Override
    public Mono<PmbClassification> classify(Claim claim) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            if (tenantId == null || tenantId.isBlank()) {
                log.debug("[pmb-classifier] no tenant context for claim {} — NOT_PMB",
                        claim != null ? claim.getId() : null);
                return Mono.just(PmbClassification.NOT_PMB);
            }
            if (claim == null || claim.getId() == null) {
                return Mono.just(PmbClassification.NOT_PMB);
            }
            List<String> diagnoses = parseCodes(claim.getDiagnosisCodes());
            if (diagnoses.isEmpty()) {
                // No diagnosis codes → nothing to probe. CMS PMB always keys
                // on ICD-10 diagnosis at minimum.
                return Mono.just(PmbClassification.NOT_PMB);
            }
            return procedureCodesFor(claim)
                    .flatMap(procedures -> probe(tenantId, claim, diagnoses, procedures));
        });
    }

    private Mono<PmbClassification> probe(String tenantId, Claim claim,
                                           List<String> diagnoses, List<String> procedures) {
        List<Probe> probes = new ArrayList<>(diagnoses.size() * Math.max(procedures.size(), 1));
        for (String diagnosis : diagnoses) {
            if (procedures.isEmpty()) {
                probes.add(new Probe(diagnosis, NO_PROCEDURE));
            } else {
                for (String procedure : procedures) {
                    probes.add(new Probe(diagnosis, procedure));
                }
            }
        }
        return Flux.fromIterable(probes)
                .concatMap(p -> fire(tenantId, claim, p))
                .filter(PmbClassification::isPmb)
                .next()
                .defaultIfEmpty(PmbClassification.NOT_PMB);
    }

    private Mono<PmbClassification> fire(String tenantId, Claim claim, Probe p) {
        PmbClassificationFact fact = new PmbClassificationFact();
        fact.setClaimId(claim.getId().toString());
        fact.setDiagnosisCode(p.diagnosis());
        fact.setProcedureCode(p.procedure());
        return ruleEvaluationService.evaluateInGroup(tenantId, "PMB_CLASSIFICATION", fact)
                .thenReturn(fact)
                .map(f -> f.isPmb()
                        ? PmbClassification.pmb(f.getPmbConditionCode())
                        : PmbClassification.NOT_PMB)
                .onErrorResume(err -> {
                    log.warn("[pmb-classifier] rule fire failed tenant={} claim={} diag={} proc={}: {}",
                            tenantId, claim.getId(), p.diagnosis(), p.procedure(), err.getMessage());
                    return Mono.just(PmbClassification.NOT_PMB);
                });
    }

    /**
     * Procedure codes come from {@code claim_lines.tariff_code} (the operator-entered
     * per-line codes) unioned with any pre-parsed {@code claim.procedure_codes}
     * JSONB payload. Line-level codes are the primary source; the JSONB field
     * is retained for pre-line legacy claims.
     */
    private Mono<List<String>> procedureCodesFor(Claim claim) {
        Set<String> combined = new LinkedHashSet<>(parseCodes(claim.getProcedureCodes()));
        return claimLineRepository.findByClaimId(claim.getId())
                .collectList()
                .map(lines -> {
                    for (ClaimLine line : lines) {
                        if (line.getTariffCode() != null && !line.getTariffCode().isBlank()) {
                            combined.add(line.getTariffCode().trim());
                        }
                    }
                    return List.copyOf(combined);
                })
                .onErrorResume(err -> {
                    log.debug("[pmb-classifier] claim-line lookup failed for {}: {}",
                            claim.getId(), err.getMessage());
                    return Mono.just(List.copyOf(combined));
                });
    }

    /** Parse a JSONB-stored String array like {@code ["A15.0","N18.6"]}. */
    static List<String> parseCodes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("[")) {
                return JSON.readValue(trimmed, STRING_LIST).stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
            }
            // Legacy comma-delimited fallback — rare but seen on old rows.
            List<String> out = new ArrayList<>();
            for (String piece : trimmed.split(",")) {
                String p = piece.trim();
                if (!p.isEmpty()) out.add(p);
            }
            return List.copyOf(out);
        } catch (Exception e) {
            log.debug("[pmb-classifier] unable to parse codes payload '{}': {}", raw, e.getMessage());
            return List.of();
        }
    }

    private record Probe(String diagnosis, String procedure) {}
}
