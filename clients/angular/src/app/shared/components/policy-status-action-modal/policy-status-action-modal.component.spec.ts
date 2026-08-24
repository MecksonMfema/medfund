import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PolicyStatusActionModalComponent, PolicyStatusActionSubmit } from './policy-status-action-modal.component';

describe('PolicyStatusActionModalComponent', () => {
  let fixture: ComponentFixture<PolicyStatusActionModalComponent>;
  let component: PolicyStatusActionModalComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PolicyStatusActionModalComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(PolicyStatusActionModalComponent);
    component = fixture.componentInstance;
    component.policySource = 'LIFE_POLICY';
    component.action = 'lapse';
    component.open = true;
    component.policyLabel = 'LIFE-000001';
    fixture.detectChanges();
  });

  it('renders the modal when open + action are set', () => {
    const modal = fixture.nativeElement.querySelector('.modal-card');
    expect(modal).toBeTruthy();
    expect(modal.textContent).toContain('Lapse policy');
    expect(modal.textContent).toContain('LIFE-000001');
  });

  it('shows a client-side error when submitting without a reason', () => {
    let emitted: PolicyStatusActionSubmit | undefined;
    component.submit.subscribe((p) => (emitted = p));
    component.reasonCode = '';
    component.onSubmit();
    expect(emitted).toBeUndefined();
    expect(component.clientError).toMatch(/reason/i);
  });

  it('emits (submit) with the reason code + optional note', () => {
    let emitted: PolicyStatusActionSubmit | undefined;
    component.submit.subscribe((p) => (emitted = p));
    component.reasonCode = 'NON_PAYMENT';
    component.reasonNote = '  because arrears  ';
    component.onSubmit();
    expect(emitted).toEqual({ reasonCode: 'NON_PAYMENT', reasonNote: 'because arrears' });
  });

  it('omits the note when it is blank', () => {
    let emitted: PolicyStatusActionSubmit | undefined;
    component.submit.subscribe((p) => (emitted = p));
    component.reasonCode = 'NON_PAYMENT';
    component.reasonNote = '   ';
    component.onSubmit();
    expect(emitted).toEqual({ reasonCode: 'NON_PAYMENT', reasonNote: undefined });
  });

  it('resets the form when the modal is re-opened', () => {
    component.reasonCode = 'MORTALITY';
    component.reasonNote = 'old';
    component.open = false;
    component.ngOnChanges({ open: { currentValue: false, previousValue: true, firstChange: false, isFirstChange: () => false } });
    component.open = true;
    component.ngOnChanges({ open: { currentValue: true, previousValue: false, firstChange: false, isFirstChange: () => false } });
    expect(component.reasonCode).toBe('');
    expect(component.reasonNote).toBe('');
  });

  it('emits (cancel) on onCancel()', () => {
    let cancelled = false;
    component.cancel.subscribe(() => (cancelled = true));
    component.onCancel();
    expect(cancelled).toBeTrue();
  });

  it('surfaces the source-specific reason vocab (LIFE has MORTALITY)', () => {
    const codes = component.reasonOptions.map(o => o.value).filter(v => v !== '');
    expect(codes).toContain('MORTALITY');
  });

  it('filters vocab when policySource changes to TRAVEL (no MORTALITY)', () => {
    component.policySource = 'TRAVEL_POLICY';
    fixture.detectChanges();
    const codes = component.reasonOptions.map(o => o.value).filter(v => v !== '');
    expect(codes).not.toContain('MORTALITY');
    expect(codes).toContain('TRIP_CANCELLED');
  });
});
