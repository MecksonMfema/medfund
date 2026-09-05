package com.medfund.rules.fact;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fact for FRAUD_TRIAGE rules (Phase 19 §A Phase 5). Fires on the
 * {@code FRAUD_TRIAGE} agenda group, invoked by claims-service's
 * {@code SiuCaseService.evaluateTriage} after a fraud_flag row lands.
 *
 * <p>Rules mutate {@link #emitCase} to signal that
 * {@code SiuCaseService} should auto-open an {@code siu_case} for the
 * flag. Field bindings match the DrlCompiler {@code fraudFlag → $fraudFlag}
 * mapping — reference in a tenant rule as e.g. {@code fraudFlag.riskScore}.
 *
 * <p>{@link #historicalMemberFlagCount} + {@link #historicalProviderHighFlagCount}
 * are populated in §B Phase 9 for pattern-recognition templates (4)+(5).
 * Phase 5 MVP leaves them at 0 — the three MVP templates key on
 * {@code riskScore}, {@code claimAmount}, and {@code providerId} only.
 */
@Getter
@Setter
@Builder
public class FraudFlagFact {

    private BigDecimal riskScore;
    private String riskLevel;
    private String insuranceLine;
    private UUID providerId;
    private UUID memberId;
    private BigDecimal claimAmount;
    private String currencyCode;
    private List<String> indicators;
    private OffsetDateTime flaggedAt;

    @Builder.Default private long historicalMemberFlagCount = 0;
    @Builder.Default private long historicalProviderHighFlagCount = 0;

    /**
     * Result flag — rules set this to {@code true} to signal case creation.
     * The DRL {@code OPEN_SIU_CASE} emitter appends {@code $fraudFlag.setEmitCase(true);}
     * to any rule with that action; SiuCaseService reads it back after the
     * agenda group fires.
     */
    @Builder.Default private boolean emitCase = false;
}
