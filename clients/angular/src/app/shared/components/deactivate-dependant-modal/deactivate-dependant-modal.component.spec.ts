import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DeactivateDependantModalComponent, DeactivateDependantPayload } from './deactivate-dependant-modal.component';

describe('DeactivateDependantModalComponent', () => {
  let fixture: ComponentFixture<DeactivateDependantModalComponent>;
  let component: DeactivateDependantModalComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DeactivateDependantModalComponent] }).compileComponents();
    fixture = TestBed.createComponent(DeactivateDependantModalComponent);
    component = fixture.componentInstance;
    component.open = true;
    component.dependantName = 'Betty Lovelace';
    fixture.detectChanges();
  });

  it('defaults effectiveDate to end of the current month', () => {
    const now = new Date();
    const eom = new Date(now.getFullYear(), now.getMonth() + 1, 0);
    const expected = `${eom.getFullYear()}-${String(eom.getMonth() + 1).padStart(2, '0')}-${String(eom.getDate()).padStart(2, '0')}`;
    expect(component.effectiveDate).toBe(expected);
  });

  it('snaps mid-month picks to end-of-month on blur', () => {
    component.effectiveDate = '2026-02-14';
    component.onEffectiveDateChange();
    expect(component.effectiveDate).toBe('2026-02-28');
  });

  it('emits payload with the snapped date on submit', () => {
    const emitted: DeactivateDependantPayload[] = [];
    component.submit.subscribe(p => emitted.push(p));
    component.effectiveDate = '2028-02-10';
    component.onSubmit();
    // 2028 is a leap year — February has 29 days.
    expect(emitted).toEqual([{ effectiveDate: '2028-02-29' }]);
  });
});
