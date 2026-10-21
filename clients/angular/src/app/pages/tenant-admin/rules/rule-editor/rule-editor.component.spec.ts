import { TestBed } from '@angular/core/testing';
import { RuleEditorComponent } from './rule-editor.component';

/**
 * Catalog-stability guard for the rule-editor action-type dropdown. The
 * plan's Phase 1 adds PAY_COMMISSION; without it in ACTION_TYPES a
 * COMMISSION-category rule can't be authored via the visual editor.
 * Mirrors the Java-side DrlCompilerTest.compile_commissionRule_* test.
 */
describe('RuleEditorComponent action-type catalogue', () => {

  it('exposes the Phase 11 PAY_COMMISSION action type', () => {
    TestBed.configureTestingModule({ imports: [RuleEditorComponent] });
    const fixture = TestBed.createComponent(RuleEditorComponent);
    fixture.componentInstance.mode = 'create';
    const ids = fixture.componentInstance.actionTypes.map(a => a.id);
    expect(ids).toContain('PAY_COMMISSION');
  });

  it('carries a valueHint on PAY_COMMISSION (RATE_CARD | KICKER)', () => {
    // The value hint drives whether the "Value" input renders + what
    // format the operator sees — required for PAY_COMMISSION.
    TestBed.configureTestingModule({ imports: [RuleEditorComponent] });
    const fixture = TestBed.createComponent(RuleEditorComponent);
    fixture.componentInstance.mode = 'create';
    const pay = fixture.componentInstance.actionTypes.find(a => a.id === 'PAY_COMMISSION');
    expect(pay?.valueHint).toContain('RATE_CARD');
    expect(pay?.valueHint).toContain('KICKER');
  });

  it('exposes the Phase 12 ACCRUE_PREMIUM action type', () => {
    // Phase 12 addition — without it in ACTION_TYPES a PREMIUM_EARNING
    // rule can't be authored via the visual editor. Mirrors the Java-side
    // DrlCompilerTest.compile_premiumEarningRule_addsAgendaGroupAndAccrue.
    TestBed.configureTestingModule({ imports: [RuleEditorComponent] });
    const fixture = TestBed.createComponent(RuleEditorComponent);
    fixture.componentInstance.mode = 'create';
    const ids = fixture.componentInstance.actionTypes.map(a => a.id);
    expect(ids).toContain('ACCRUE_PREMIUM');
  });

  it('carries a valueHint on ACCRUE_PREMIUM (EARNING_METHOD DSL)', () => {
    // The hint documents the EARNING_METHOD:<name>[:<loading-pct>] DSL that
    // AccruePremiumEmitter parses — required for authors to compose it.
    TestBed.configureTestingModule({ imports: [RuleEditorComponent] });
    const fixture = TestBed.createComponent(RuleEditorComponent);
    fixture.componentInstance.mode = 'create';
    const accrue = fixture.componentInstance.actionTypes.find(a => a.id === 'ACCRUE_PREMIUM');
    expect(accrue?.valueHint).toContain('EARNING_METHOD');
    expect(accrue?.valueHint).toContain('DAILY_LINEAR');
    expect(accrue?.valueHint).toContain('MONTHLY_24THS');
    expect(accrue?.valueHint).toContain('LINEAR_WITH_LOADING');
  });
});
