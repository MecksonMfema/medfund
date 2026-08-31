import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DueDateBannerComponent } from './due-date-banner.component';
import { DueDateBannerRow } from '../../../../../../core/services/regulatory-due-dates.service';

function row(overrides: Partial<DueDateBannerRow>): DueDateBannerRow {
  return {
    reportKey: 'IPEC_QUARTERLY_RETURN',
    reportLabel: 'IPEC — quarterly return (ZW)',
    cadence: 'QUARTERLY',
    periodStart: '2026-04-01',
    periodEnd: '2026-06-30',
    dueDate: '2026-07-30',
    daysUntilDue: -31,
    submissionStatus: 'PENDING',
    severity: 'RED',
    ...overrides,
  };
}

describe('DueDateBannerComponent', () => {
  let fixture: ComponentFixture<DueDateBannerComponent>;
  let component: DueDateBannerComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DueDateBannerComponent] })
      .compileComponents();
    fixture = TestBed.createComponent(DueDateBannerComponent);
    component = fixture.componentInstance;
  });

  it('renders overdue banner with banner-red class', () => {
    component.row = row({});
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const banner = el.querySelector('[data-testid="due-date-banner"]')!;
    expect(banner.classList).toContain('banner-red');
    expect(banner.textContent).toContain('Overdue by 31 days');
    expect(banner.textContent).toContain('2026-07-30');
  });

  it('renders "Due tomorrow" for daysUntilDue=1 with AMBER severity', () => {
    component.row = row({ daysUntilDue: 1, severity: 'AMBER' });
    fixture.detectChanges();
    const banner = fixture.nativeElement.querySelector('[data-testid="due-date-banner"]');
    expect(banner.classList).toContain('banner-amber');
    expect(banner.textContent).toContain('Due tomorrow');
  });

  it('renders "Due today" for daysUntilDue=0', () => {
    component.row = row({ daysUntilDue: 0, severity: 'RED' });
    fixture.detectChanges();
    const banner = fixture.nativeElement.querySelector('[data-testid="due-date-banner"]');
    expect(banner.textContent).toContain('Due today');
  });

  it('renders "Due in N days" for daysUntilDue > 1', () => {
    component.row = row({ daysUntilDue: 12, severity: 'INFO' });
    fixture.detectChanges();
    const banner = fixture.nativeElement.querySelector('[data-testid="due-date-banner"]');
    expect(banner.classList).toContain('banner-info');
    expect(banner.textContent).toContain('Due in 12 days');
  });

  it('renders "Filed" when SUBMITTED regardless of days-until-due', () => {
    component.row = row({ daysUntilDue: -16, submissionStatus: 'SUBMITTED', severity: 'INFO' });
    fixture.detectChanges();
    const banner = fixture.nativeElement.querySelector('[data-testid="due-date-banner"]');
    expect(banner.classList).toContain('banner-info');
    expect(banner.textContent).toContain('Filed');
    expect(banner.textContent).not.toContain('Overdue');
  });

  it('renders "Amended" when submissionStatus is AMENDED', () => {
    component.row = row({ submissionStatus: 'AMENDED', severity: 'INFO' });
    fixture.detectChanges();
    const banner = fixture.nativeElement.querySelector('[data-testid="due-date-banner"]');
    expect(banner.textContent).toContain('Amended');
  });
});
