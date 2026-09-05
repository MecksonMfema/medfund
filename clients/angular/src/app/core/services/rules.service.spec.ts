import { RULE_CATEGORIES, RuleCategory } from './rules.service';

/**
 * Catalog-stability guard for the rules-engine category list. Enum values
 * are string keys stored in tenant rows — deleting or renaming one
 * silently breaks existing rules. If a value is added, the RULE_CATEGORIES
 * array must be updated in the same commit (mirrors the Java-side
 * RuleCategoryTest guard).
 */
describe('RULE_CATEGORIES catalogue', () => {

  it('includes the Phase 11 COMMISSION category', () => {
    // Phase 11 addition — exposes PAY_COMMISSION rules to the tenant-admin
    // rule editor. Must be present or the visual editor cannot filter
    // to COMMISSION rules and the category dropdown loses the option.
    const ids = RULE_CATEGORIES.map(c => c.id);
    expect(ids).toContain('COMMISSION');
  });

  it('includes the Phase 12 PREMIUM_EARNING category', () => {
    // Phase 12 addition — exposes ACCRUE_PREMIUM rules to the tenant-admin
    // rule editor. Without this entry the category dropdown hides the
    // option and tenants can't author earning-method rules from the UI.
    const ids: RuleCategory[] = RULE_CATEGORIES.map(c => c.id);
    expect(ids).toContain('PREMIUM_EARNING');
  });

  it('includes the Phase 10 REINSURANCE category', () => {
    // Regression guard — the plan's Phase 1 must not accidentally remove
    // sibling category entries when extending the array.
    const ids = RULE_CATEGORIES.map(c => c.id);
    expect(ids).toContain('REINSURANCE');
  });

  it('includes the Phase 14 ACTUARIAL category', () => {
    // Phase 14 addition — exposes SELECT_LDF rules to the tenant-admin
    // rule editor so tenants can override the default volume-weighted
    // LDF method used by IBNR / loss triangle chain-ladder compute.
    const ids: RuleCategory[] = RULE_CATEGORIES.map(c => c.id);
    expect(ids).toContain('ACTUARIAL');
  });

  it('includes the Phase 15 §9 IFRS17_MODEL category', () => {
    // Phase 15 §9 addition — exposes SELECT_IFRS17_MODEL rules to the
    // tenant-admin rule editor so tenants can author per-line IFRS 17
    // measurement-model (PAA / GMM / VFA) selection rules on top of the
    // 8 industry-default rows seeded by V165__seed_ifrs17_model_default_rules.
    const ids: RuleCategory[] = RULE_CATEGORIES.map(c => c.id);
    expect(ids).toContain('IFRS17_MODEL');
  });

  it('includes the Phase 16 §Phase-15 REGULATORY_PARAMETER category', () => {
    // Phase 16 §Phase-15 REG15 addition — exposes SET_REGULATORY_PARAMETER
    // rules to the tenant-admin rule editor so tenants can override numeric
    // parameters that would otherwise resolve from bundled regulator YAML
    // defaults (IPEC min_solvency_ratio, CMS non_healthcare_cost_target,
    // NAIC provision percentages, ...). Agenda-gated so overrides only fire
    // when RegulatoryParameterResolver focuses the group per lookup.
    const ids: RuleCategory[] = RULE_CATEGORIES.map(c => c.id);
    expect(ids).toContain('REGULATORY_PARAMETER');
  });

  it('includes the Phase 17 §B REG7 PMB_CLASSIFICATION category', () => {
    // Phase 17 §B REG7 addition — exposes SET_PMB_CLASSIFICATION rules to
    // the tenant-admin rule editor so scheme admins can top up the
    // industry_default_v1 seed (V172) with scheme-specific PMB entries
    // beyond the representative sample. Agenda-gated so classifier only
    // fires when RulesEnginePmbClassifier focuses the group at
    // adjudication + PmbBackfillJob run time.
    const ids: RuleCategory[] = RULE_CATEGORIES.map(c => c.id);
    expect(ids).toContain('PMB_CLASSIFICATION');
  });

  it('includes the Phase 19 §B Phase 10 FRAUD_TRIAGE category', () => {
    // Phase 19 §B Phase 10 addition — exposes OPEN_SIU_CASE +
    // SUPPRESS_SIU_CASE rules to the tenant-admin rule editor so
    // tenants can configure the six FRAUD_TRIAGE templates (threshold,
    // threshold+amount, watchlisted provider, repeat-offender, provider
    // high-flag, never-auto-open). Agenda-gated so rules only fire when
    // SiuCaseService.evaluateTriage focuses the group per fraud_flag.
    const ids: RuleCategory[] = RULE_CATEGORIES.map(c => c.id);
    expect(ids).toContain('FRAUD_TRIAGE');
  });

  it('has no duplicate category ids', () => {
    const ids = RULE_CATEGORIES.map(c => c.id);
    const unique = new Set(ids);
    expect(unique.size).toBe(ids.length);
  });
});
