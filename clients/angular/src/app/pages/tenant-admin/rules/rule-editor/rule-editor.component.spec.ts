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
});
