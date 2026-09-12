import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { KpiDashboardComponent } from './kpi-dashboard.component';
import {
  ExecutiveKpiService,
  KpiDashboardResponse,
  KpiReportData,
  KpiTrendPoint,
} from '../../../../../core/services/executive-kpi.service';
import { CurrencyService } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';

function envelope(data: KpiReportData): ReportResponse<KpiReportData> {
  return {
    reportKey: 'LOSS_RATIO_KPI',
    period: { periodStart: '2026-08-01', periodEnd: '2026-09-01', grain: 'MONTHLY' },
    reportingCurrency: 'USD',
    data,
    perCurrency: {},
    fxRates: {},
    warnings: [],
    generatedAt: '2026-09-05T00:00:00Z',
  };
}

function trend(values: number[]): KpiTrendPoint[] {
  return values.map((v, i) => ({
    periodStart: `2026-${String(i + 1).padStart(2, '0')}-01`,
    periodEnd:   `2026-${String(i + 2).padStart(2, '0')}-01`,
    composite: {
      compositeRatio: v, compositeNumerator: v * 100, compositeDenominator: 100,
      basisNote: null, perCurrency: {},
    },
    perCurrency: {},
    warnings: [],
  }));
}

describe('KpiDashboardComponent', () => {
  let kpi: jasmine.SpyObj<ExecutiveKpiService>;

  beforeEach(() => {
    kpi = jasmine.createSpyObj<ExecutiveKpiService>('ExecutiveKpiService', ['dashboard', 'trend', 'exportExcel']);

    TestBed.configureTestingModule({
      imports: [KpiDashboardComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNoopAnimations(),
        { provide: ExecutiveKpiService,   useValue: kpi },
        { provide: CurrencyService,       useValue: { listForTenant: () => of([]) } },
        { provide: TenantService,         useValue: { getTenantId: () => 'tnt-1' } },
      ],
    });
  });

  function stubDefault(response?: KpiDashboardResponse) {
    kpi.dashboard.and.returnValue(of(response ?? {
      tiles: {
        LOSS_RATIO_KPI: envelope({
          compositeRatio: 0.6, compositeNumerator: 60, compositeDenominator: 100,
          basisNote: null, perCurrency: {},
        }),
        EXPENSE_RATIO: envelope({
          compositeRatio: 0.2, compositeNumerator: 20, compositeDenominator: 100,
          basisNote: null, perCurrency: {},
        }),
        COMBINED_RATIO: envelope({
          compositeRatio: 0.8, compositeNumerator: 80, compositeDenominator: 100,
          basisNote: 'MIXED_LOSS_EARNED_EXPENSE_WRITTEN', perCurrency: {},
        }),
        CLAIMS_FREQUENCY: envelope({
          compositeRatio: 0.05, compositeNumerator: 5, compositeDenominator: 100,
          basisNote: null, perCurrency: {},
        }),
        AVERAGE_SEVERITY: envelope({
          compositeRatio: 250, compositeNumerator: 250, compositeDenominator: 1,
          basisNote: null, perCurrency: {},
        }),
      },
    }));
    kpi.trend.and.callFake(() => of(trend([0.5, 0.55, 0.6])));
  }

  it('fetches the dashboard + a trend per KPI on init', () => {
    stubDefault();
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();

    expect(kpi.dashboard).toHaveBeenCalledTimes(1);
    expect(kpi.trend).toHaveBeenCalledTimes(5);
    expect(kpi.trend).toHaveBeenCalledWith('LOSS_RATIO_KPI', jasmine.any(Object), 12);
    expect(fixture.componentInstance.loading).toBeFalse();
    expect(Object.keys(fixture.componentInstance.tiles).length).toBe(5);
  });

  it('populates a sparkline series per tile from the trend responses', () => {
    stubDefault();
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();

    const series = fixture.componentInstance.sparklineFor('LOSS_RATIO_KPI');
    expect(series.length).toBe(3);
    expect(series[0].value).toBe(0.5);
    expect(series[2].value).toBe(0.6);
  });

  it('derives an upward direction when the series ends higher than it starts', () => {
    stubDefault();
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.directionFor('LOSS_RATIO_KPI')).toBe('up');
  });

  it('changing insurance line re-fires both calls', () => {
    stubDefault();
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();
    kpi.dashboard.calls.reset();
    kpi.trend.calls.reset();

    fixture.componentInstance.onLineChange('HEALTH');

    expect(kpi.dashboard).toHaveBeenCalledTimes(1);
    expect(kpi.trend).toHaveBeenCalledTimes(5);
    expect(fixture.componentInstance.filters.insuranceLine).toBe('HEALTH');
  });

  it('picking a scheme sets the filter and re-fetches', () => {
    stubDefault();
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();
    kpi.dashboard.calls.reset();

    fixture.componentInstance.onSchemePicked({ id: 'sch-1', label: 'Elite Plan' });

    expect(fixture.componentInstance.schemeId).toBe('sch-1');
    expect(fixture.componentInstance.schemeLabel).toBe('Elite Plan');
    expect(fixture.componentInstance.filters.schemeId).toBe('sch-1');
    expect(kpi.dashboard).toHaveBeenCalledTimes(1);
  });

  it('clearing scheme unsets the filter and re-fetches', () => {
    stubDefault();
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.onSchemePicked({ id: 'sch-1', label: 'Elite Plan' });
    kpi.dashboard.calls.reset();

    fixture.componentInstance.onSchemePicked(null);

    expect(fixture.componentInstance.schemeId).toBeNull();
    expect(fixture.componentInstance.filters.schemeId).toBeUndefined();
    expect(kpi.dashboard).toHaveBeenCalledTimes(1);
  });

  it('picking a producer sets the filter and re-fetches', () => {
    stubDefault();
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();
    kpi.dashboard.calls.reset();

    fixture.componentInstance.onProducerPicked({ id: 'prod-1', label: 'Alpha Brokers' });

    expect(fixture.componentInstance.producerId).toBe('prod-1');
    expect(fixture.componentInstance.producerLabel).toBe('Alpha Brokers');
    expect(fixture.componentInstance.filters.producerId).toBe('prod-1');
    expect(kpi.dashboard).toHaveBeenCalledTimes(1);
  });

  it('403 on dashboard flips the disabled flag without setting errorMessage', () => {
    kpi.dashboard.and.returnValue(throwError(() => ({ status: 403 })));
    kpi.trend.and.returnValue(of([]));
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.disabled).toBeTrue();
    expect(fixture.componentInstance.errorMessage).toBeNull();
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('non-403 errors surface as errorMessage', () => {
    kpi.dashboard.and.returnValue(throwError(() =>
      ({ status: 500, error: { detail: 'boom' } })));
    kpi.trend.and.returnValue(of([]));
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.errorMessage).toBe('boom');
    expect(fixture.componentInstance.disabled).toBeFalse();
  });

  it('trend call failure for one KPI does not break the other four sparklines', () => {
    stubDefault();
    kpi.trend.and.callFake((key: string) =>
      key === 'AVERAGE_SEVERITY'
        ? throwError(() => ({ status: 500 }))
        : of(trend([0.5, 0.6])),
    );
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.sparklineFor('LOSS_RATIO_KPI').length).toBe(2);
    expect(fixture.componentInstance.sparklineFor('AVERAGE_SEVERITY').length).toBe(0);
  });

  it('exportKpi passes the tile envelope period into ExecutiveKpiService.exportExcel', () => {
    stubDefault();
    kpi.exportExcel.and.returnValue(of(new Blob(['x'], { type: 'application/octet-stream' })));
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.exportKpi('LOSS_RATIO_KPI');

    expect(kpi.exportExcel).toHaveBeenCalledWith('LOSS_RATIO_KPI', jasmine.objectContaining({
      periodStart: '2026-08-01',
      periodEnd:   '2026-09-01',
    }));
    expect(fixture.componentInstance.isExporting('LOSS_RATIO_KPI')).toBeFalse();
  });

  it('exportKpi noops when no envelope is loaded for the key', () => {
    stubDefault({ tiles: {} });
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.exportKpi('LOSS_RATIO_KPI');
    expect(kpi.exportExcel).not.toHaveBeenCalled();
  });

  it('export failure surfaces errorMessage and clears exportingKey', () => {
    stubDefault();
    kpi.exportExcel.and.returnValue(throwError(() => ({ error: { detail: 'export boom' } })));
    const fixture = TestBed.createComponent(KpiDashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.exportKpi('LOSS_RATIO_KPI');
    expect(fixture.componentInstance.errorMessage).toBe('export boom');
    expect(fixture.componentInstance.isExporting('LOSS_RATIO_KPI')).toBeFalse();
  });
});
