package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.all;
import static com.medfund.rules.template.TemplateBuilder.cond;
import static com.medfund.rules.template.TemplateBuilder.rule;

/**
 * Seed templates for the {@code FRAUD_TRIAGE} rule category (Phase 19 §A
 * Phase 5 + §B Phase 9). Templates using {@code OPEN_SIU_CASE} flip
 * {@code fraudFlag.emitCase = true}; the {@code neverAutoOpen} template
 * uses {@code SUPPRESS_SIU_CASE} and flips it back to {@code false}.
 * Claims-service's {@code SiuCaseService.evaluateTriage} reads
 * {@code emitCase} after the agenda group fires; when true, an
 * {@code siu_case} is auto-opened.
 *
 * <p>Rules in this category are agenda-gated (see
 * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) so they only fire when
 * {@code SiuCaseService} explicitly focuses the {@code FRAUD_TRIAGE}
 * group per fraud_flag — never during the stage-7 tenant-rule sweep.
 *
 * <p>Six shipped templates:
 * <ol>
 *   <li><b>threshold</b> — fires when risk_score exceeds a tenant-configured
 *       minimum. The most common starter — mirrors the default policy in
 *       {@code SiuCaseService}.</li>
 *   <li><b>threshold+amount</b> — fires when risk_score AND claim_amount
 *       both exceed minima. Right for tenants who only want to auto-open
 *       high-value + high-risk claims.</li>
 *   <li><b>watchlist</b> — fires when {@code providerId} matches a tenant-
 *       configured watchlisted provider. Tenants clone this template once
 *       per provider on their watchlist; the Operator enum has no {@code IN}
 *       today so a per-provider EQUALS rule is the current DSL shape.</li>
 *   <li><b>repeat-offender</b> (§B) — fires when the flag's member already
 *       has ≥ minCount HIGH-risk flags in the 90-day lookback and this flag
 *       is itself HIGH. Requires {@code historicalMemberFlagCount} — populated
 *       by {@code SiuCaseService.evaluateTriage} via
 *       {@code FraudFlagRepository.countHighRiskForMemberSince}.</li>
 *   <li><b>provider-high-flag-pattern</b> (§B) — fires when the flag's
 *       provider already has ≥ minCount HIGH-risk flags in the 90-day
 *       lookback. Requires {@code historicalProviderHighFlagCount}.</li>
 *   <li><b>never-auto-open</b> (§B) — explicit off-switch; sets
 *       {@code emitCase = false} at the lowest priority so it fires last
 *       and overrides any preceding {@code OPEN_SIU_CASE} rule for the
 *       same tenant. Combine with tenant admin manually opening cases
 *       via {@code claims:siu:create}.</li>
 * </ol>
 */
@Component
public class FraudTriageTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.FRAUD_TRIAGE;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            rule("FRAUD1 - Auto-open above risk threshold",
                 "Auto-open an SIU case when the AI risk_score exceeds the "
                       + "configured minimum. Change the threshold to raise or "
                       + "lower the sensitivity - mirrors the SiuCaseService "
                       + "default policy (0.85) so authoring this rule is optional.",
                 RuleCategory.FRAUD_TRIAGE, 100,
                 all(cond("fraudFlag.riskScore", "GREATER_THAN", 0.85)),
                 openCase("Above risk threshold")),

            rule("FRAUD2 - Auto-open large claim + high risk",
                 "Auto-open an SIU case when BOTH the AI risk_score AND the "
                       + "claim amount exceed configured minima. Right for tenants "
                       + "who prefer to manually review small-value flags but always "
                       + "want an investigation on high-value + high-risk claims.",
                 RuleCategory.FRAUD_TRIAGE, 90,
                 all(cond("fraudFlag.riskScore",   "GREATER_THAN", 0.70),
                     cond("fraudFlag.claimAmount", "GREATER_THAN", 5000)),
                 openCase("High-value + high-risk")),

            rule("FRAUD3 - Auto-open for watchlisted provider",
                 "Auto-open an SIU case whenever any flag lands against a "
                       + "provider the tenant has watchlisted. Change the providerId "
                       + "UUID to the target provider - clone this rule once per "
                       + "watchlisted provider (the DSL has no IN operator today, "
                       + "so per-provider EQUALS rules are the current shape).",
                 RuleCategory.FRAUD_TRIAGE, 80,
                 all(cond("fraudFlag.providerId", "EQUALS",
                     "00000000-0000-0000-0000-000000000000")),
                 openCase("Watchlisted provider")),

            // ── §B Phase 9 — pattern-recognition templates ──────────────

            rule("FRAUD4 - Auto-open on member repeat-offender pattern",
                 "Auto-open an SIU case when the flag's member already has "
                       + "≥ minCount HIGH-risk flags in the 90-day lookback AND this "
                       + "flag is itself HIGH. Change minCount to raise or lower "
                       + "sensitivity. Requires historicalMemberFlagCount populated by "
                       + "SiuCaseService.evaluateTriage (Phase 9); MVP-Phase-5 flags "
                       + "left this at 0 so this rule never fires until §B lands.",
                 RuleCategory.FRAUD_TRIAGE, 70,
                 all(cond("fraudFlag.historicalMemberFlagCount", "GREATER_THAN_OR_EQUALS", 3),
                     cond("fraudFlag.riskLevel", "EQUALS", "HIGH")),
                 openCase("Member repeat-offender pattern")),

            rule("FRAUD5 - Auto-open on provider high-flag pattern",
                 "Auto-open an SIU case when the flag's provider already has "
                       + "≥ minCount HIGH-risk flags in the 90-day lookback. Right "
                       + "for tenants who want to catch systemic provider fraud "
                       + "without waiting for a specific claim to breach the "
                       + "score threshold.",
                 RuleCategory.FRAUD_TRIAGE, 60,
                 all(cond("fraudFlag.historicalProviderHighFlagCount",
                          "GREATER_THAN_OR_EQUALS", 5)),
                 openCase("Provider high-flag pattern")),

            // Salience 1 — fires LAST after any higher-priority OPEN rule; the
            // suppress action flips emitCase back to false so the final state
            // per fact is "no auto-open" for this tenant. Combine with tenant
            // admins manually opening cases via claims:siu:create.
            rule("FRAUD6 - Never auto-open (manual triage only)",
                 "Explicit off-switch. Fires on every fraud_flag (risk_score > 0) "
                       + "at the lowest priority (salience 1) so it overrides any "
                       + "preceding OPEN_SIU_CASE rule in the same evaluation. "
                       + "Tenants who prefer a fully-manual SIU workflow load this "
                       + "template alongside - or instead of - the OPEN templates.",
                 RuleCategory.FRAUD_TRIAGE, 1,
                 all(cond("fraudFlag.riskScore", "GREATER_THAN", 0)),
                 suppressCase("Manual-triage-only tenant policy"))
        );
    }

    private static RuleAction openCase(String message) {
        RuleAction a = new RuleAction();
        a.setType("OPEN_SIU_CASE");
        a.setMessage(message);
        return a;
    }

    private static RuleAction suppressCase(String message) {
        RuleAction a = new RuleAction();
        a.setType("SUPPRESS_SIU_CASE");
        a.setMessage(message);
        return a;
    }
}
