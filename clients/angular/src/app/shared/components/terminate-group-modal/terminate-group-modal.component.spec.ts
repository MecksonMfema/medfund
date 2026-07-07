import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TerminateGroupModalComponent, TerminateGroupPayload } from './terminate-group-modal.component';

describe('TerminateGroupModalComponent', () => {
  let fixture: ComponentFixture<TerminateGroupModalComponent>;
  let component: TerminateGroupModalComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [TerminateGroupModalComponent] }).compileComponents();
    fixture = TestBed.createComponent(TerminateGroupModalComponent);
    component = fixture.componentInstance;
    component.open = true;
    component.groupName = 'Acme Ltd';
    fixture.detectChanges();
  });

  it('defaults effectiveDate to end of the current month', () => {
    const now = new Date();
    const eom = new Date(now.getFullYear(), now.getMonth() + 1, 0);
    const expected = `${eom.getFullYear()}-${String(eom.getMonth() + 1).padStart(2, '0')}-${String(eom.getDate()).padStart(2, '0')}`;
    expect(component.effectiveDate).toBe(expected);
  });

  it('snaps mid-month picks to end-of-month on blur', () => {
    component.effectiveDate = '2026-07-15';
    component.onEffectiveDateChange();
    expect(component.effectiveDate).toBe('2026-07-31');
  });

  it('emits a payload with the snapped date on submit', () => {
    const emitted: TerminateGroupPayload[] = [];
    component.submit.subscribe(p => emitted.push(p));
    component.effectiveDate = '2026-04-15';
    component.reason = 'Contract ended';
    component.onSubmit();
    expect(emitted).toEqual([{ effectiveDate: '2026-04-30', reason: 'Contract ended' }]);
  });

  it('emits cancel', () => {
    let cancelled = false;
    component.cancel.subscribe(() => (cancelled = true));
    component.onCancel();
    expect(cancelled).toBeTrue();
  });

  it('reset clears reason and re-seeds the default date', () => {
    component.reason = 'Something';
    component.effectiveDate = '2026-02-15';
    component.reset();
    expect(component.reason).toBe('');
    expect(component.effectiveDate).toMatch(/^\d{4}-\d{2}-(28|29|30|31)$/);
  });
});
