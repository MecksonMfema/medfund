package com.medfund.claims.service;

import com.medfund.claims.client.ClaimsFxConverter;
import com.medfund.claims.costshare.AppliedRuleAction;
import com.medfund.claims.costshare.BenefitCostShareReader;
import com.medfund.claims.costshare.CostShareConfig;
import com.medfund.claims.costshare.MemberCostShareAccumulatorReader;
import com.medfund.claims.costshare.SchemeCostShareReader;
import com.medfund.claims.dto.AdjudicationResult.CostShareBreakdown;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compute the 7-bucket cost-share breakdown for an adjudicated claim (G3).
 * Called by {@link AdjudicationDecisionEngine} on the auto-approve branch —
 * reject / manual-review branches skip the calculator, so the entity's
 * cost-share columns stay NULL until an operator later approves.
 *
 * <p>Formula (all in claim currency):
 * <pre>
 *   allowedAmount        = ruleAdjustedTotal (post modifiers)
 *   deductibleApplied    = min(allowedAmount, scs.deductible - acc.deductibleMet)
 *   copayAmount          = APPLY_COPAY rule override, else sum of per-line
 *                          benefit copay (fx-converted from benefit currency, G6)
 *   coinsuranceAmount    = (allowed - deductible - copay) * per-line coinsurance_rate
 *   notCoveredAmount     = 0 (reserved — benefit-reject line hooks not wired here)
 *   shortfallAmount      = CoPaymentService.calculate — AHFOZ (claimed - tariff) delta
 *   memberResponsibility = deductible + copay + coinsurance + notCovered
 *                          [+ shortfall when scs.shortfallPolicy = RECOVER_FROM_MEMBER]
 * </pre>
 *
 * <p>The OOP-max cap is applied after the sum: any bucket that would push
 * {@code oopMet + memberResponsibility} past {@code out_of_pocket_max} is
 * scaled down (test case (e) of the Phase 2 success criteria).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostShareCalculator {

    private final SchemeCostShareReader schemeCostShareReader;
    private final BenefitCostShareReader benefitCostShareReader;
    private final MemberCostShareAccumulatorReader accumulatorReader;
    private final CoPaymentService coPaymentService;
    private final ClaimsFxConverter fx;

    /**
     * Compute the breakdown. All monetary output is in {@code claim.currencyCode};
     * benefit-currency inputs are fx-converted via {@link ClaimsFxConverter} using
     * {@code claim.serviceDate} as the {@code asOf} (G6).
     */
    public Mono<CostShareBreakdown> compute(Claim claim, List<ClaimLine> lines,
                                            List<AppliedRuleAction> ruleActions,
                                            BigDecimal ruleAdjustedTotal) {
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuidOrNull(tenantIdStr);
            LocalDate asOf = claim.getServiceDate() != null ? claim.getServiceDate() : LocalDate.now();
            int policyYear = asOf.getYear();

            List<UUID> benefitIds = lines.stream()
                    .map(ClaimLine::getBenefitId)
                    .filter(id -> id != null)
                    .distinct()
                    .toList();

            Mono<CostShareConfig.Scheme> schemeMono =
                    schemeCostShareReader.findEffective(claim.getSchemeId(), policyYear, asOf)
                            .defaultIfEmpty(placeholderScheme(claim, policyYear));

            Mono<Map<UUID, CostShareConfig.Benefit>> benefitMapMono =
                    benefitCostShareReader.findEffective(benefitIds, asOf)
                            .collectMap(CostShareConfig.Benefit::schemeBenefitId, b -> b);

            // Accumulator lookup honours the scheme's deductible_scope: FAMILY = family pot
            // (dependantId=null); INDIVIDUAL/EMBEDDED = per-dependant row (or member's own
            // when the claim is against the principal). Actual dual-write for EMBEDDED
            // happens on commit, not here — the calculator only *reads* accumulated met.
            Mono<CostShareConfig.Accumulator> accMono = schemeMono.flatMap(scs -> {
                UUID depForRead = "FAMILY".equals(scs.deductibleScope()) ? null : claim.getDependantId();
                return accumulatorReader.findFor(claim.getMemberId(), depForRead,
                                claim.getSchemeId(), policyYear)
                        .defaultIfEmpty(CostShareConfig.Accumulator.empty(
                                claim.getMemberId(), depForRead, claim.getSchemeId(),
                                policyYear, claim.getCurrencyCode()));
            });

            Mono<CoPaymentService.CoPaymentResult> copayResultMono =
                    coPaymentService.calculate(claim, lines);

            return Mono.zip(schemeMono, benefitMapMono, accMono, copayResultMono)
                    .flatMap(t -> assembleBreakdown(
                            claim, lines, ruleActions, ruleAdjustedTotal,
                            t.getT1(), t.getT2(), t.getT3(), t.getT4(),
                            tenantId, asOf));
        });
    }

    private Mono<CostShareBreakdown> assembleBreakdown(
            Claim claim, List<ClaimLine> lines, List<AppliedRuleAction> ruleActions,
            BigDecimal ruleAdjustedTotal, CostShareConfig.Scheme scs,
            Map<UUID, CostShareConfig.Benefit> perBenefit,
            CostShareConfig.Accumulator acc,
            CoPaymentService.CoPaymentResult copayResult,
            UUID tenantId, LocalDate asOf) {

        BigDecimal allowedRaw = ruleAdjustedTotal != null ? ruleAdjustedTotal : claim.getClaimedAmount();
        final BigDecimal allowed = allowedRaw != null ? allowedRaw : BigDecimal.ZERO;

        // 1. Deductible — min(allowed, remaining scheme deductible)
        BigDecimal remainingDeductible = nz(scs.deductible()).subtract(nz(acc.deductibleMet())).max(BigDecimal.ZERO);
        final BigDecimal deductibleApplied = allowed.min(remainingDeductible);
        final BigDecimal postDeductible = allowed.subtract(deductibleApplied);

        // 2. Copay — rule override wins (G4). Otherwise sum benefit-level copay per line
        //    (in benefit currency, fx-converted to claim currency, G6).
        Mono<BigDecimal> copayMono = resolveCopay(ruleActions, claim, lines, perBenefit, postDeductible,
                allowed, tenantId, asOf);

        return copayMono.map(copayAmountInitial -> {
            BigDecimal copayAmount = copayAmountInitial;
            BigDecimal postDeductibleAndCopay = postDeductible.subtract(copayAmount).max(BigDecimal.ZERO);

            // 3. Coinsurance — weighted per-line rate applied to the post-deductible-post-copay pool.
            //    Weight by claimed amount so a mixed-benefit claim proportions correctly.
            BigDecimal coinsurance = computeCoinsurance(lines, perBenefit, postDeductibleAndCopay);

            // 4. Not-covered — reserved. Rules-engine reject-line hooks would flow here; today's
            //    per-line rejection lives in claim_lines.status='REJECTED' and reduces
            //    ruleAdjustedTotal upstream, so nothing else lands here.
            BigDecimal notCovered = BigDecimal.ZERO;

            // 5. Shortfall — from CoPaymentService (AHFOZ claimed-vs-tariff delta).
            //    Latent currency-mismatch (F1) — tariff is benefit-currency, claimed is
            //    claim-currency; captured as follow-up, not fixed here.
            BigDecimal shortfall = nz(copayResult.totalCoPayment());

            // 6. Member responsibility — sum of the recover-from-member buckets.
            boolean recoverShortfall = !"ABSORB_BY_FUND".equals(scs.shortfallPolicy());
            BigDecimal memberResp = deductibleApplied
                    .add(copayAmount).add(coinsurance).add(notCovered)
                    .add(recoverShortfall ? shortfall : BigDecimal.ZERO);

            // 7. OOP-max cap. When acc.oopMet + memberResp would exceed out_of_pocket_max,
            //    scale each recoverable bucket down proportionally so the sum lands exactly
            //    at the cap. (Deductible is preserved — pre-OOP by convention.)
            if (scs.outOfPocketMax() != null) {
                BigDecimal remainingOop = scs.outOfPocketMax().subtract(nz(acc.oopMet())).max(BigDecimal.ZERO);
                if (memberResp.compareTo(remainingOop) > 0) {
                    BigDecimal overage = memberResp.subtract(remainingOop);
                    BigDecimal scalable = copayAmount.add(coinsurance).add(recoverShortfall ? shortfall : BigDecimal.ZERO);
                    if (scalable.signum() > 0) {
                        BigDecimal factor = BigDecimal.ONE.subtract(overage.divide(scalable, 10, RoundingMode.HALF_UP));
                        factor = factor.max(BigDecimal.ZERO);
                        copayAmount = copayAmount.multiply(factor);
                        coinsurance = coinsurance.multiply(factor);
                        if (recoverShortfall) shortfall = shortfall.multiply(factor);
                        memberResp = deductibleApplied.add(copayAmount).add(coinsurance).add(notCovered)
                                .add(recoverShortfall ? shortfall : BigDecimal.ZERO);
                    }
                }
            }

            return new CostShareBreakdown(
                    scale(allowed), scale(deductibleApplied), scale(copayAmount), scale(coinsurance),
                    scale(notCovered), scale(shortfall), scale(memberResp));
        });
    }

    private Mono<BigDecimal> resolveCopay(List<AppliedRuleAction> ruleActions,
                                          Claim claim, List<ClaimLine> lines,
                                          Map<UUID, CostShareConfig.Benefit> perBenefit,
                                          BigDecimal postDeductible, BigDecimal allowed,
                                          UUID tenantId, LocalDate asOf) {
        BigDecimal ruleOverride = findRuleCopayOverride(ruleActions, allowed);
        if (ruleOverride != null) {
            // Rule override wins per G4. A waiver (FIXED:0) short-circuits to zero.
            return Mono.just(ruleOverride);
        }
        // Sum benefit-level copay per line, fx-converting from benefit currency to claim currency.
        return Flux.fromIterable(lines)
                .flatMap(line -> {
                    CostShareConfig.Benefit bcs = line.getBenefitId() != null ? perBenefit.get(line.getBenefitId()) : null;
                    if (bcs == null || bcs.copayType() == null) return Mono.just(BigDecimal.ZERO);
                    BigDecimal raw = perLineCopay(line, bcs, postDeductible);
                    return fx.convert(raw, bcs.currencyCode(), claim.getCurrencyCode(), asOf, tenantId);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal perLineCopay(ClaimLine line, CostShareConfig.Benefit bcs, BigDecimal postDeductible) {
        // TIERED without a tier-selection channel from the pipeline falls back to zero —
        // MVP treats tier resolution as a future enhancement (F5 defers the tiers table).
        switch (bcs.copayType()) {
            case "FLAT" -> {
                return nz(bcs.copayAmount());
            }
            case "PERCENT" -> {
                BigDecimal pct = nz(bcs.copayPercentage()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                BigDecimal amt = nz(line.getClaimedAmount()).multiply(pct);
                return bcs.copayMax() != null ? amt.min(bcs.copayMax()) : amt;
            }
            default -> {
                return BigDecimal.ZERO;
            }
        }
    }

    /**
     * Look for the first {@code APPLY_COPAY} in the rule-fire list; that's the
     * highest-priority per Drools salience convention (G4). Returns the resolved
     * BigDecimal (in claim currency) or null when no rule fired.
     */
    private BigDecimal findRuleCopayOverride(List<AppliedRuleAction> ruleActions, BigDecimal allowed) {
        if (ruleActions == null || ruleActions.isEmpty()) return null;
        for (AppliedRuleAction a : ruleActions) {
            if (!"APPLY_COPAY".equalsIgnoreCase(a.type())) continue;
            return parseCopayValue(a.value(), allowed);
        }
        return null;
    }

    /**
     * Parse the template's action value string. Two shapes used by
     * {@code CoPaymentTemplates}: {@code "FIXED:25"} → flat 25.00,
     * {@code "20"} → 20% of allowed. Numeric zero is a waiver (G14).
     */
    private static BigDecimal parseCopayValue(String raw, BigDecimal allowed) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        String trimmed = raw.trim();
        if (trimmed.startsWith("FIXED:")) {
            try { return new BigDecimal(trimmed.substring("FIXED:".length())); }
            catch (NumberFormatException e) { return BigDecimal.ZERO; }
        }
        try {
            BigDecimal pct = new BigDecimal(trimmed).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            return nz(allowed).multiply(pct);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal computeCoinsurance(List<ClaimLine> lines,
                                          Map<UUID, CostShareConfig.Benefit> perBenefit,
                                          BigDecimal pool) {
        if (pool.signum() <= 0 || lines.isEmpty()) return BigDecimal.ZERO;
        BigDecimal claimedTotal = lines.stream()
                .map(ClaimLine::getClaimedAmount).filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (claimedTotal.signum() == 0) return BigDecimal.ZERO;

        BigDecimal coinsurance = BigDecimal.ZERO;
        for (ClaimLine line : lines) {
            CostShareConfig.Benefit bcs = line.getBenefitId() != null ? perBenefit.get(line.getBenefitId()) : null;
            if (bcs == null || bcs.coinsuranceRate() == null || bcs.coinsuranceRate().signum() <= 0) continue;
            BigDecimal lineClaimed = nz(line.getClaimedAmount());
            BigDecimal weight = lineClaimed.divide(claimedTotal, 10, RoundingMode.HALF_UP);
            coinsurance = coinsurance.add(pool.multiply(weight).multiply(bcs.coinsuranceRate()));
        }
        return coinsurance;
    }

    private CostShareConfig.Scheme placeholderScheme(Claim claim, int policyYear) {
        // No scheme_cost_share row = no deductible, no OOP-max, shortfall recovered from member.
        // Keeps the pipeline running for tenants that haven't authored a config yet.
        return new CostShareConfig.Scheme(null, claim.getSchemeId(), policyYear,
                null, null, "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER",
                claim.getCurrencyCode(), LocalDate.MIN, null);
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static BigDecimal scale(BigDecimal v) { return v != null ? v.setScale(4, RoundingMode.HALF_UP) : null; }

    private static UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
