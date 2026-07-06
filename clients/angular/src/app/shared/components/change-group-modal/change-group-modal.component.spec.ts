import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChangeGroupModalComponent, ChangeGroupPayload } from './change-group-modal.component';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

/**
 * Focused guards for the change-group modal:
 *
 * <ul>
 *   <li>Submit blocked with a friendly error when no group is picked.
 *   <li>Mid-month effective_date snaps to day 1 on blur so the backend's
 *       CHECK constraint (day = 1) never trips.
 *   <li>Backdated flag flips based on the effective date, driving the
 *       amber "back-dated" hint in the template.
 *   <li>Submit emits exactly the payload members.service expects.
 * </ul>
 */
describe('ChangeGroupModalComponent', () => {
  let fixture: ComponentFixture<ChangeGroupModalComponent>;
  let component: ChangeGroupModalComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChangeGroupModalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    fixture = TestBed.createComponent(ChangeGroupModalComponent);
    component = fixture.componentInstance;
    component.open = true;
    fixture.detectChanges();
  });

  it('rejects submit when no target group is picked', () => {
    let emitted: ChangeGroupPayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.targetGroupId = '';
    component.onSubmit();

    expect(emitted).toBeUndefined();
    expect(component.error).toMatch(/target group/i);
  });

  it('snaps mid-month effective_date to day 1 on blur', () => {
    // The <input type="date"> lets an operator type or paste an
    // arbitrary date — a mid-month value would trip V048's CHECK
    // (extract(day from effective_date) = 1). Modal must be
    // defensive; front-end onBlur is the last line of defence.
    component.effectiveDate = '2026-09-15';
    component.onEffectiveDateChange();
    expect(component.effectiveDate).toBe('2026-09-01');
  });

  it('flips isBackdated() based on effective date', () => {
    // Prior month → back-dated
    const prev = new Date();
    prev.setDate(1);
    prev.setMonth(prev.getMonth() - 1);
    component.effectiveDate = prev.toISOString().slice(0, 10);
    expect(component.isBackdated()).toBe(true);

    // Next month → forward-dated
    const next = new Date();
    next.setDate(1);
    next.setMonth(next.getMonth() + 1);
    component.effectiveDate = next.toISOString().slice(0, 10);
    expect(component.isBackdated()).toBe(false);
  });

  it('emits the payload members.service expects when submitted', () => {
    let emitted: ChangeGroupPayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.targetGroupId = '11111111-2222-3333-4444-555555555555';
    component.effectiveDate = '2026-09-01';
    component.reason = 'moving offices';
    component.onSubmit();

    expect(emitted).toEqual({
      targetGroupId: '11111111-2222-3333-4444-555555555555',
      effectiveDate: '2026-09-01',
      reason: 'moving offices',
    });
  });

  it('omits reason when the operator left it blank', () => {
    // reason is optional; sending an empty string would clutter the
    // audit trail. Trim + undefined is the intent.
    let emitted: ChangeGroupPayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.targetGroupId = '11111111-2222-3333-4444-555555555555';
    component.effectiveDate = '2026-09-01';
    component.reason = '   ';
    component.onSubmit();

    expect(emitted?.reason).toBeUndefined();
  });

  it('emits cancel when Cancel button-equivalent is invoked', () => {
    let cancelled = false;
    component.cancel.subscribe(() => (cancelled = true));
    component.onCancel();
    expect(cancelled).toBe(true);
  });

  it('reset() clears every field so a re-open shows a clean form', () => {
    component.targetGroupId = 'abc';
    component.reason = 'stale reason';
    component.error = 'stale error';
    component.reset();

    expect(component.targetGroupId).toBe('');
    expect(component.reason).toBe('');
    expect(component.error).toBeNull();
  });
});
