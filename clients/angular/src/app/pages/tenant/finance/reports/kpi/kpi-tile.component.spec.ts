import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { KpiTileComponent } from './kpi-tile.component';

describe('KpiTileComponent', () => {
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [KpiTileComponent],
      providers: [provideRouter([]), provideNoopAnimations()],
    });
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.returnValue(Promise.resolve(true));
  });

  it('formats composite as a percentage', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'LOSS_RATIO_KPI';
    fixture.componentInstance.label = 'Loss ratio';
    fixture.componentInstance.composite = 0.6421;
    fixture.detectChanges();

    const composite: HTMLElement = fixture.nativeElement.querySelector('.composite');
    expect(composite.textContent?.trim()).toBe('64.2%');
  });

  it('shows a placeholder dash when composite is null', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'LOSS_RATIO_KPI';
    fixture.componentInstance.label = 'Loss ratio';
    fixture.componentInstance.composite = null;
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.composite').textContent?.trim()).toBe('-');
  });

  it('renders the mixed-basis tooltip only when basisNote is the mixed marker', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'COMBINED_RATIO';
    fixture.componentInstance.label = 'Combined ratio';
    fixture.componentInstance.basisNote = 'MIXED_LOSS_EARNED_EXPENSE_WRITTEN';
    fixture.detectChanges();

    const note: HTMLElement = fixture.nativeElement.querySelector('.basis-note');
    expect(note).toBeTruthy();
    expect(note.getAttribute('title'))
      .toBe('Mixed basis: loss ratio on earned premium, expense ratio on written premium (NAIC convention).');
  });

  it('renders per-currency chips sorted alphabetically', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'LOSS_RATIO_KPI';
    fixture.componentInstance.label = 'Loss ratio';
    fixture.componentInstance.perCurrency = {
      USD: { ratio: 0.6, numerator: 60, denominator: 100, currencyCode: 'USD' },
      EUR: { ratio: 0.5, numerator: 50, denominator: 100, currencyCode: 'EUR' },
      GBP: { ratio: 0.7, numerator: 70, denominator: 100, currencyCode: 'GBP' },
    };
    fixture.detectChanges();

    const chips = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('.chip'));
    expect(chips.map(c => c.textContent!.trim().split(/\s+/)[0])).toEqual(['EUR', 'GBP', 'USD']);
  });

  it('navigates to the drill target on click when one is defined', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'LOSS_RATIO_KPI';
    fixture.componentInstance.label = 'Loss ratio';
    fixture.detectChanges();

    fixture.componentInstance.onTileClick();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tenant/finance/reports/billing-vs-claims');
  });

  it('does not navigate for COMBINED_RATIO (mixed basis has no single drill target)', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'COMBINED_RATIO';
    fixture.componentInstance.label = 'Combined ratio';
    fixture.detectChanges();

    fixture.componentInstance.onTileClick();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(fixture.componentInstance.hasDrillTarget).toBeFalse();
  });

  it('does not navigate while loading', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'LOSS_RATIO_KPI';
    fixture.componentInstance.label = 'Loss ratio';
    fixture.componentInstance.loading = true;
    fixture.detectChanges();

    fixture.componentInstance.onTileClick();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('renders warnings when present', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'LOSS_RATIO_KPI';
    fixture.componentInstance.label = 'Loss ratio';
    fixture.componentInstance.warnings = ['claims-service call failed'];
    fixture.detectChanges();

    const warnings = fixture.nativeElement.querySelectorAll('.warning');
    expect(warnings.length).toBe(1);
    expect((warnings[0] as HTMLElement).textContent).toContain('claims-service call failed');
  });

  it('emits exportRequested and stops propagation when the export button is clicked', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'LOSS_RATIO_KPI';
    fixture.componentInstance.label = 'Loss ratio';
    fixture.detectChanges();

    const emitted: string[] = [];
    fixture.componentInstance.exportRequested.subscribe(k => emitted.push(k));

    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('.btn-export');
    expect(btn.textContent?.trim()).toBe('Export XLSX');
    btn.click();
    expect(emitted).toEqual(['LOSS_RATIO_KPI']);

    // Drill-nav didn't fire because the click's propagation was stopped.
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('disables the export button and shows "Exporting…" while exporting', () => {
    const fixture = TestBed.createComponent(KpiTileComponent);
    fixture.componentInstance.key = 'LOSS_RATIO_KPI';
    fixture.componentInstance.label = 'Loss ratio';
    fixture.componentInstance.exporting = true;
    fixture.detectChanges();

    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('.btn-export');
    expect(btn.disabled).toBeTrue();
    expect(btn.textContent?.trim()).toBe('Exporting…');
  });
});
