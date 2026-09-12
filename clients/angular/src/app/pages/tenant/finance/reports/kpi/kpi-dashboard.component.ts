import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import {
  ExecutiveKpiService,
  KpiDashboardResponse,
  KpiFilters,
  KpiKey,
  KpiReportData,
  KpiTrendPoint,
  KpiValue,
} from '../../../../../core/services/executive-kpi.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { INSURANCE_LINES } from '../../../../../core/models/insurance-lines';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import {
  EntityPickerComponent,
  EntityPickerSelection,
} from '../../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SparklinePoint } from '../../../../../shared/components/charts/sparkline/sparkline.component';
import { KpiTileComponent } from './kpi-tile.component';

/**
 * Executive KPI dashboard (Phase 7) — the visible surface for the five
 * ratios composed server-side by {@code KpiComposerService}. Renders a batch
 * dashboard call plus five parallel 12-month trend fetches, each backing a
 * {@link KpiTileComponent}.
 *
 * <p>Filter chips: insurance line (fixed enum), scheme (debounced search per
 * {@code feedback_no_raw_id_inputs}), producer (debounced search), reporting
 * currency (tenant-configured currencies). Any change triggers a full
 * refresh.
 *
 * <p>The batch endpoint 403s if every KPI is disabled for the tenant — the
 * page then renders an empty state without tiles instead of crashing.
 */
@Component({
  selector: 'app-kpi-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, SelectComponent, EntityPickerComponent, IconComponent, KpiTileComponent],
  templateUrl: './kpi-dashboard.component.html',
  styleUrl: './kpi-dashboard.component.scss',
})
export class KpiDashboardComponent implements OnInit {
  readonly KPI_KEYS: KpiKey[] = [
    'LOSS_RATIO_KPI',
    'EXPENSE_RATIO',
    'COMBINED_RATIO',
    'CLAIMS_FREQUENCY',
    'AVERAGE_SEVERITY',
  ];

  readonly tileLabels: Record<KpiKey, string> = {
    LOSS_RATIO_KPI:    'Loss ratio',
    EXPENSE_RATIO:     'Acquisition ratio',
    COMBINED_RATIO:    'Combined ratio',
    CLAIMS_FREQUENCY:  'Claims frequency',
    AVERAGE_SEVERITY:  'Average severity',
  };

  // Loss / expense / combined / frequency are ratios (rendered as %).
  // Average severity is a currency amount — showing `3.25 × 100 = 325%`
  // for a $3.25 severity is nonsense; render it as a decimal.
  readonly tileFormats: Record<KpiKey, 'ratio' | 'amount'> = {
    LOSS_RATIO_KPI:    'ratio',
    EXPENSE_RATIO:     'ratio',
    COMBINED_RATIO:    'ratio',
    CLAIMS_FREQUENCY:  'ratio',
    AVERAGE_SEVERITY:  'amount',
  };

  loading      = false;
  errorMessage: string | null = null;
  disabled     = false;

  filters: KpiFilters = {};
  tiles: Partial<Record<KpiKey, ReportResponse<KpiReportData>>> = {};
  sparklines: Partial<Record<KpiKey, SparklinePoint[]>> = {};
  trendDirections: Partial<Record<KpiKey, 'up' | 'down' | 'flat'>> = {};
  exportingKey: KpiKey | null = null;

  currencies: TenantCurrencyConfig[] = [];
  periodLabel = '';

  schemeId: string | null = null;
  schemeLabel: string | null = null;

  producerId: string | null = null;
  producerLabel: string | null = null;

  constructor(
    private kpiService: ExecutiveKpiService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.loadCurrencies();
    this.refresh();
  }

  private loadCurrencies(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.currencyService.listForTenant(tenantId).subscribe({
      next: cs => {
        this.currencies = cs.filter(c => c.isActive);
      },
      error: () => { /* non-fatal */ },
    });
  }

  get insuranceLineOptions(): SelectOption[] {
    return [
      { value: '', label: 'All insurance lines' },
      ...INSURANCE_LINES.map(l => ({ value: l.value, label: l.label })),
    ];
  }

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Tenant default' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  onLineChange(value: string | number | null): void {
    this.filters = { ...this.filters, insuranceLine: value ? String(value) : undefined };
    this.refresh();
  }

  onCurrencyChange(value: string | number | null): void {
    this.filters = { ...this.filters, reportingCurrency: value ? String(value) : undefined };
    this.refresh();
  }

  onSchemePicked(sel: EntityPickerSelection | null): void {
    if (sel) {
      this.schemeId = sel.id;
      this.schemeLabel = sel.label;
      this.filters = { ...this.filters, schemeId: sel.id };
    } else {
      this.schemeId = null;
      this.schemeLabel = null;
      this.filters = { ...this.filters, schemeId: undefined };
    }
    this.refresh();
  }

  onProducerPicked(sel: EntityPickerSelection | null): void {
    if (sel) {
      this.producerId = sel.id;
      this.producerLabel = sel.label;
      this.filters = { ...this.filters, producerId: sel.id };
    } else {
      this.producerId = null;
      this.producerLabel = null;
      this.filters = { ...this.filters, producerId: undefined };
    }
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.errorMessage = null;
    this.disabled = false;

    // Each trend call is independent — swallow individual failures so one
    // disabled KPI doesn't wipe the other four sparklines.
    const trendCalls = this.KPI_KEYS.reduce((acc, k) => {
      acc[k] = this.kpiService.trend(k, this.filters, 12).pipe(
        catchError(() => of([] as KpiTrendPoint[])),
      );
      return acc;
    }, {} as Record<KpiKey, ReturnType<ExecutiveKpiService['trend']>>);

    forkJoin({
      dashboard: this.kpiService.dashboard(this.filters).pipe(
        catchError(err => {
          if (err?.status === 403) {
            this.disabled = true;
          } else {
            this.errorMessage = err?.error?.detail || err?.error?.title
              || 'Failed to load KPI dashboard.';
          }
          return of<KpiDashboardResponse>({ tiles: {} });
        }),
      ),
      trends: forkJoin(trendCalls),
    }).subscribe({
      next: ({ dashboard, trends }) => {
        this.tiles = dashboard.tiles;
        for (const k of this.KPI_KEYS) {
          const points = trends[k] ?? [];
          this.sparklines[k] = points.map(pt => ({
            name:  pt.periodStart,
            value: pt.composite?.compositeRatio ?? 0,
          }));
          this.trendDirections[k] = deriveDirection(points);
        }
        const firstEnvelope = this.KPI_KEYS
          .map(k => this.tiles[k])
          .find((t): t is ReportResponse<KpiReportData> => !!t);
        this.periodLabel = firstEnvelope?.period
          ? `${firstEnvelope.period.periodStart} → ${firstEnvelope.period.periodEnd}`
          : '';
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load KPI dashboard.';
        this.loading = false;
      },
    });
  }

  tileEnvelope(key: KpiKey): ReportResponse<KpiReportData> | null {
    return this.tiles[key] ?? null;
  }

  /** True when at least one KPI tile has data. Drives the empty-state
   *  branch — when no tile came back and the tenant is not fully-disabled
   *  we render the "No KPI data for this period" surface instead of a
   *  blank grid. */
  get hasAnyTile(): boolean {
    return this.KPI_KEYS.some(k => !!this.tiles[k]);
  }

  compositeFor(key: KpiKey): number | null {
    return this.tiles[key]?.data?.compositeRatio ?? null;
  }

  perCurrencyFor(key: KpiKey): Record<string, KpiValue> {
    return this.tiles[key]?.data?.perCurrency ?? {};
  }

  basisFor(key: KpiKey): string | null {
    return this.tiles[key]?.data?.basisNote ?? null;
  }

  warningsFor(key: KpiKey): string[] {
    return this.tiles[key]?.warnings ?? [];
  }

  sparklineFor(key: KpiKey): SparklinePoint[] {
    return this.sparklines[key] ?? [];
  }

  directionFor(key: KpiKey): 'up' | 'down' | 'flat' {
    return this.trendDirections[key] ?? 'flat';
  }

  isExporting(key: KpiKey): boolean {
    return this.exportingKey === key;
  }

  /** Handler for per-tile export button. Uses the currently-displayed period
   *  from the envelope; noops if the envelope is missing. */
  exportKpi(key: KpiKey): void {
    const env = this.tiles[key];
    if (!env || !env.period || this.exportingKey) return;
    this.exportingKey = key;
    this.kpiService.exportExcel(key, {
      ...this.filters,
      periodStart: env.period.periodStart,
      periodEnd:   env.period.periodEnd,
    }).subscribe({
      next: blob => {
        downloadBlob(blob, `${key.toLowerCase()}-${env.period!.periodStart}-to-${env.period!.periodEnd}.xlsx`);
        this.exportingKey = null;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to export KPI workbook.';
        this.exportingKey = null;
      },
    });
  }
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/** Compare the first and last non-null point to derive an arrow direction. */
function deriveDirection(points: KpiTrendPoint[]): 'up' | 'down' | 'flat' {
  const values = points
    .map(p => p.composite?.compositeRatio)
    .filter((v): v is number => v !== null && v !== undefined);
  if (values.length < 2) return 'flat';
  const first = values[0];
  const last = values[values.length - 1];
  const delta = last - first;
  const epsilon = Math.max(Math.abs(first), Math.abs(last)) * 0.01;
  if (delta > epsilon)  return 'up';
  if (delta < -epsilon) return 'down';
  return 'flat';
}
