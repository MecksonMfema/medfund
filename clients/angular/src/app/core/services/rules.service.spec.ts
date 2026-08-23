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

  it('has no duplicate category ids', () => {
    const ids = RULE_CATEGORIES.map(c => c.id);
    const unique = new Set(ids);
    expect(unique.size).toBe(ids.length);
  });
});
