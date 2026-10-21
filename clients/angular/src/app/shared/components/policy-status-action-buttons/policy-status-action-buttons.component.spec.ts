import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PolicyStatusActionButtonsComponent } from './policy-status-action-buttons.component';
import { PolicyAction } from '../../../core/services/policy-lifecycle-action-registry.service';

describe('PolicyStatusActionButtonsComponent', () => {
  let fixture: ComponentFixture<PolicyStatusActionButtonsComponent>;
  let component: PolicyStatusActionButtonsComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PolicyStatusActionButtonsComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(PolicyStatusActionButtonsComponent);
    component = fixture.componentInstance;
    component.currentStatus = 'active';
    component.policySource = 'LIFE_POLICY';
    fixture.detectChanges();
  });

  it('renders three action buttons for active policies', () => {
    const buttons: HTMLButtonElement[] =
      fixture.nativeElement.querySelectorAll('button[data-action]');
    expect(buttons.length).toBe(3);
    expect(Array.from(buttons).map(b => b.dataset['action'])).toEqual([
      'lapse', 'terminate', 'suspend',
    ]);
  });

  it('renders zero buttons for terminated policies', () => {
    component.currentStatus = 'terminated';
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll('button[data-action]');
    expect(buttons.length).toBe(0);
  });

  it('emits (actionClicked) with the picked action on click', () => {
    let emitted: PolicyAction | undefined;
    component.actionClicked.subscribe((a) => (emitted = a));
    const lapseBtn: HTMLButtonElement =
      fixture.nativeElement.querySelector('button[data-action="lapse"]');
    lapseBtn.click();
    expect(emitted).toBe('lapse');
  });

  it('applies the danger class to Terminate and success class to Reinstate', () => {
    component.currentStatus = 'suspended';
    fixture.detectChanges();
    const reinstate: HTMLButtonElement =
      fixture.nativeElement.querySelector('button[data-action="reinstate"]');
    const terminate: HTMLButtonElement =
      fixture.nativeElement.querySelector('button[data-action="terminate"]');
    expect(reinstate.className).toContain('success');
    expect(terminate.className).toContain('danger');
  });
});
