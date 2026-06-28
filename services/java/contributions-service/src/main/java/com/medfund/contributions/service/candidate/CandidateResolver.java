package com.medfund.contributions.service.candidate;

import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Per-insurance-line projection of "what to bill" for a period.
 *
 * <p>The HEALTH resolver projects members + dependants. The MOTOR
 * resolver projects vehicles. LIFE/FUNERAL/TRAVEL/DISABILITY project
 * their respective policy tables. Each returns the same
 * {@link PersonCandidate} row contract so the downstream pricing +
 * invoice rollup logic in {@code BillingService} stays line-agnostic.
 *
 * <p>Resolvers are picked up by Spring component scan and routed by
 * {@link #supportedLine()} matching the scheme's
 * {@code insurance_line}. Adding a new line is "drop one class with
 * {@code @Component}" — no edit to {@code BillingService} dispatch.
 */
public interface CandidateResolver {

    /**
     * Insurance-line code this resolver handles (e.g. {@code "HEALTH"},
     * {@code "VEHICLE"}). Matched against
     * {@code schemes.insurance_line} at dispatch time.
     */
    String supportedLine();

    /**
     * Project the entities this line bills for the given period.
     *
     * @param groupIds       optional filter (only candidates in these groups)
     * @param memberIds      optional filter (only these member ids; for
     *                       asset lines this filters by owner_member_id)
     * @param periodStart    inclusive start of billing period
     * @param periodEnd      inclusive end of billing period
     * @param pricingModel   {@code STANDARD} | {@code INDIVIDUAL} |
     *                       {@code AI_DRIVEN}; resolvers use this to
     *                       gate per-entity {@code billing_override_amount}
     */
    Flux<PersonCandidate> resolveCandidates(
            List<UUID> groupIds,
            List<UUID> memberIds,
            LocalDate periodStart,
            LocalDate periodEnd,
            String pricingModel);
}
