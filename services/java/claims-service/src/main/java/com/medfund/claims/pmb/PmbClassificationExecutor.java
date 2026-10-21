package com.medfund.claims.pmb;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs PMB classification against a single claim and persists the outcome.
 * Called from the adjudication path once the standard rule sweep finishes;
 * Phase 16's {@link PmbBackfillJob} does the batch equivalent for historical
 * rows. Both routes must agree on the shape of {@code is_pmb} +
 * {@code pmb_condition_code} writes and audit events — the executor keeps
 * that logic in one place.
 *
 * <p>Idempotent: a claim whose current DB state already matches the
 * classifier verdict is left untouched (no save, no audit event). Errors from
 * the classifier are swallowed with a WARN — an adjudication path can't fail
 * just because a PMB rule mis-fired.
 *
 * <p>Phase 17 §B REG7.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmbClassificationExecutor {

    private final PmbClassifier classifier;
    private final ClaimRepository claimRepository;
    private final AuditPublisher auditPublisher;

    /**
     * Classify {@code claim} and persist the outcome. Returns the (possibly
     * mutated) claim so the adjudication chain stays linear.
     */
    public Mono<Claim> classifyAndPersist(Claim claim, String actorId, String actorEmail) {
        if (claim == null || claim.getId() == null) {
            return Mono.justOrEmpty(claim);
        }
        String correlationId = UUID.randomUUID().toString();
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return classifier.classify(claim)
                    .defaultIfEmpty(PmbClassification.NOT_PMB)
                    .flatMap(verdict -> apply(tenantId, actorId, actorEmail, claim, verdict, correlationId))
                    .onErrorResume(err -> {
                        log.warn("[pmb-exec] classify failed tenant={} claim={} correlation={}: {}",
                                tenantId, claim.getId(), correlationId, err.getMessage());
                        return Mono.just(claim);
                    });
        });
    }

    private Mono<Claim> apply(String tenantId, String actorId, String actorEmail, Claim claim,
                              PmbClassification verdict, String correlationId) {
        if (!classificationChanged(claim, verdict)) {
            return Mono.just(claim);
        }
        Map<String, Object> oldValue = snapshot(claim);
        claim.setIsPmb(verdict.isPmb());
        claim.setPmbConditionCode(verdict.conditionCode());
        Map<String, Object> newValue = snapshot(claim);
        return claimRepository.save(claim)
                .flatMap(saved -> publishAudit(tenantId, actorId, actorEmail, saved,
                                oldValue, newValue, correlationId)
                        .thenReturn(saved));
    }

    private static boolean classificationChanged(Claim claim, PmbClassification verdict) {
        boolean currentPmb = Boolean.TRUE.equals(claim.getIsPmb());
        if (currentPmb != verdict.isPmb()) return true;
        return !Objects.equals(claim.getPmbConditionCode(), verdict.conditionCode());
    }

    private static Map<String, Object> snapshot(Claim claim) {
        Map<String, Object> m = new HashMap<>();
        m.put("isPmb", Boolean.TRUE.equals(claim.getIsPmb()));
        m.put("pmbConditionCode", claim.getPmbConditionCode());
        return m;
    }

    private Mono<Void> publishAudit(String tenantId, String actorId, String actorEmail,
                                     Claim claim, Map<String, Object> oldValue,
                                     Map<String, Object> newValue, String correlationId) {
        String claimId = claim.getId().toString();
        String claimNumber = claim.getClaimNumber() != null ? claim.getClaimNumber() : claimId;
        AuditEvent event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "claim.pmb_classification",
                claimId,
                "PMB classification for claim " + claimNumber,
                "PMB_CLASSIFY",
                actorId,
                actorEmail,
                oldValue,
                newValue,
                new String[]{"isPmb", "pmbConditionCode"},
                correlationId
        );
        return auditPublisher.publish(event);
    }
}
