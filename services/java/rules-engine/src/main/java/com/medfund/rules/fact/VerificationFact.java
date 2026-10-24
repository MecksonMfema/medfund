package com.medfund.rules.fact;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Fact for {@code VERIFICATION} rules (KYC horizontal, Plan A Phase 3). Fires on
 * the {@code VERIFICATION} agenda group, invoked by kyc-service when it needs to
 * decide per-line / per-jurisdiction verification policy — never during the
 * stage-7 tenant-rule sweep in claims-service.
 *
 * <p>Line-neutral by design (Critical Rule framing): a verification subject is a
 * party of some {@link #subjectType} (MEMBER, PROVIDER, …), optionally in the
 * context of an {@link #insuranceLine} and a {@link #country}. Per-line /
 * per-jurisdiction requirements are expressed as <em>rule content</em> over
 * these fields, not as engine code (Critical Rule #5).
 *
 * <p>Rules mutate the two result flags, which kyc-service reads back after the
 * agenda group fires:
 * <ul>
 *   <li>{@link #requireManualReview} — route this verification to a human
 *       reviewer (e.g. a sanctions/PEP screening hit) via
 *       {@code REQUIRE_MANUAL_REVIEW}.</li>
 *   <li>{@link #gateActivation} — the verification tier is sufficient to gate
 *       activation of the subject's policy/benefit via {@code GATE_ACTIVATION}.</li>
 * </ul>
 * Field bindings match the {@code DrlCompiler} {@code verification → $verification}
 * mapping — reference in a tenant rule as e.g. {@code verification.screeningResult}.
 */
@Getter
@Setter
@Builder
public class VerificationFact {

    private String subjectType;
    private String insuranceLine;
    private String country;
    private List<String> documentTypesPresent;
    private int verificationLevel;
    private String screeningResult;

    /** Result flag — rules set this {@code true} to force a human review. */
    @Builder.Default private boolean requireManualReview = false;

    /** Result flag — rules set this {@code true} when the tier may gate activation. */
    @Builder.Default private boolean gateActivation = false;
}
