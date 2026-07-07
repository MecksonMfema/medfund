import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SwapDependantModalComponent, SwapDependantPayload } from './swap-dependant-modal.component';
import { Dependant } from '../../../core/services/members.service';

/**
 * Focused guards for the swap-dependant modal:
 *
 * <ul>
 *   <li>Dependant list filters to active/suspended only — a
 *       deactivated or swapped row can't be re-promoted.
 *   <li>Submit blocked until a dependant is picked.
 *   <li>Payload matches members.service.requestSwap signature.
 *   <li>Mid-month effective_date snaps to day 1 on blur.
 * </ul>
 */
describe('SwapDependantModalComponent', () => {
  let fixture: ComponentFixture<SwapDependantModalComponent>;
  let component: SwapDependantModalComponent;

  const dep = (id: string, status: string): Dependant => ({
    id, memberId: 'm-1',
    firstName: 'Dep', lastName: id.toUpperCase(),
    relationship: 'spouse', status,
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SwapDependantModalComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(SwapDependantModalComponent);
    component = fixture.componentInstance;
    component.open = true;
  });

  it('eligible() excludes deactivated / removed / swapped rows', () => {
    // Only active or suspended dependants can be promoted. A
    // deactivated one has no cover, and a swapped one is already
    // pointing at a different row via swapped_to_id.
    component.dependants = [
      dep('a', 'active'),
      dep('b', 'suspended'),
      dep('c', 'deactivated'),
      dep('d', 'swapped'),
      dep('e', 'enrolled'),
    ];
    fixture.detectChanges();

    const ids = component.eligible().map(d => d.id).sort();
    expect(ids).toEqual(['a', 'b']);
  });

  it('rejects submit when no dependant is picked', () => {
    let emitted: SwapDependantPayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.dependants = [dep('a', 'active')];
    component.selectedDependantId = '';
    fixture.detectChanges();
    component.onSubmit();

    expect(emitted).toBeUndefined();
    expect(component.error).toMatch(/dependant/i);
  });

  it('emits the payload members.service.requestSwap expects', () => {
    let emitted: SwapDependantPayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.dependants = [dep('d-1', 'active')];
    component.selectedDependantId = 'd-1';
    component.effectiveDate = '2026-09-01';
    component.reason = 'spouse takes over';
    fixture.detectChanges();
    component.onSubmit();

    expect(emitted).toEqual({
      dependantId: 'd-1',
      effectiveDate: '2026-09-01',
      reason: 'spouse takes over',
    });
  });

  it('snaps mid-month effective_date to day 1 on blur', () => {
    component.effectiveDate = '2026-09-15';
    component.onEffectiveDateChange();
    expect(component.effectiveDate).toBe('2026-09-01');
  });

  it('selected() returns the picked dependant object', () => {
    // Template preview panel binds to selected() to name the promoted
    // person. Guards against the picker id and the source list going
    // out of sync.
    component.dependants = [dep('a', 'active'), dep('b', 'active')];
    component.selectedDependantId = 'b';
    expect(component.selected()?.id).toBe('b');
  });
});
