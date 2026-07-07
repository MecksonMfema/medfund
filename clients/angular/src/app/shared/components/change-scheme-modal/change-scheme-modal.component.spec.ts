import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChangeSchemeModalComponent, ChangeSchemePayload } from './change-scheme-modal.component';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

describe('ChangeSchemeModalComponent', () => {
  let fixture: ComponentFixture<ChangeSchemeModalComponent>;
  let component: ChangeSchemeModalComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChangeSchemeModalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    fixture = TestBed.createComponent(ChangeSchemeModalComponent);
    component = fixture.componentInstance;
    component.open = true;
    component.currentSchemeId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    fixture.detectChanges();
  });

  it('rejects submit when no target scheme is picked', () => {
    let emitted: ChangeSchemePayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.toSchemeId = '';
    component.onSubmit();

    expect(emitted).toBeUndefined();
    expect(component.error).toMatch(/target scheme/i);
  });

  it('rejects submit when target scheme equals current scheme', () => {
    let emitted: ChangeSchemePayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.toSchemeId = component.currentSchemeId!;
    component.onSubmit();

    expect(emitted).toBeUndefined();
    expect(component.error).toMatch(/differ/i);
  });

  it('rejects submit when current scheme is unknown', () => {
    let emitted: ChangeSchemePayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.currentSchemeId = null;
    component.toSchemeId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
    component.onSubmit();

    expect(emitted).toBeUndefined();
    expect(component.error).toMatch(/current scheme/i);
  });

  it('snaps mid-month effective_date to day 1 on blur', () => {
    component.effectiveDate = '2026-09-15';
    component.onEffectiveDateChange();
    expect(component.effectiveDate).toBe('2026-09-01');
  });

  it('flips isBackdated() based on effective date', () => {
    const prev = new Date();
    prev.setDate(1);
    prev.setMonth(prev.getMonth() - 1);
    component.effectiveDate = prev.toISOString().slice(0, 10);
    expect(component.isBackdated()).toBe(true);

    const next = new Date();
    next.setDate(1);
    next.setMonth(next.getMonth() + 1);
    component.effectiveDate = next.toISOString().slice(0, 10);
    expect(component.isBackdated()).toBe(false);
  });

  it('emits the payload members.service expects when submitted', () => {
    let emitted: ChangeSchemePayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.toSchemeId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
    component.effectiveDate = '2026-09-01';
    component.reason = 'upgrade to family plan';
    component.onSubmit();

    expect(emitted).toEqual({
      fromSchemeId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      toSchemeId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
      effectiveDate: '2026-09-01',
      reason: 'upgrade to family plan',
    });
  });

  it('omits reason when the operator left it blank', () => {
    let emitted: ChangeSchemePayload | undefined;
    component.submit.subscribe((p) => (emitted = p));

    component.toSchemeId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
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
    component.toSchemeId = 'abc';
    component.reason = 'stale reason';
    component.error = 'stale error';
    component.reset();

    expect(component.toSchemeId).toBe('');
    expect(component.reason).toBe('');
    expect(component.error).toBeNull();
  });
});
