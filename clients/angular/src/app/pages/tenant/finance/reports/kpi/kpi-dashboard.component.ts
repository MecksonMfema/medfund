import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
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
import { ContributionsService, Scheme } from '../../../../../core/services/contributions.service';
import { Producer, ProducerService } from '../../../../../core/services/producer.service';
import { INSURANCE_LINES } from '../../../../../core/models/insurance-lines';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
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
  imports: [CommonModule, FormsModule, SelectComponent, IconComponent, KpiTileComponent],
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
  schemeSearchQuery = '';
  schemeMatches: Scheme[] = [];
  schemeSearching = false;
  private schemeSearchTimer: ReturnType<typeof setTimeout> | null = null;

  producerId: string | null = null;
  producerLabel: string | null = null;
  producerSearchQuery = '';
  producerMatches: Producer[] = [];
  producerSearching = false;
  private producerSearchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private kpiService: ExecutiveKpiService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private contributions: ContributionsService,
    private producerSvc: ProducerService,
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

  onSchemeSearchChange(): void {
    if (this.schemeSearchTimer) clearTimeout(this.schemeSearchTimer);
    const q = this.schemeSearchQuery.trim();
    if (!q) { this.schemeMatches = []; return; }
    this.schemeSearching = true;
    this.schemeSearchTimer = setTimeout(() => {
      this.contributions.searchSchemes(q, 10).subscribe({
        next: rows => { this.schemeMatches = rows; this.schemeSearching = false; },
        error: () => { this.schemeMatches = []; this.schemeSearching = false; },
      });
    }, 300);
  }

  pickScheme(s: Scheme): void {
    this.schemeId    = s.id;
    this.schemeLabel = s.name;
    this.schemeSearchQuery = '';
    this.schemeMatches = [];
    this.filters = { ...this.filters, schemeId: s.id };
    this.refresh();
  }

  clearScheme(): void {
    this.schemeId    = null;
    this.schemeLabel = null;
    this.filters = { ...this.filters, schemeId: undefined };
    this.refresh();
  }

  onProducerSearchChange(): void {
    if (this.producerSearchTimer) clearTimeout(this.producerSearchTimer);
    const q = this.producerSearchQuery.trim();
    if (!q) { this.producerMatches = []; return; }
    this.producerSearching = true;
    this.producerSearchTimer = setTimeout(() => {
      this.producerSvc.searchProducers(q, 10).subscribe({
        next: rows => { this.producerMatches = rows; this.producerSearching = false; },
        error: () => { this.producerMatches = []; this.producerSearching = false; },
      });
    }, 300);
  }

  pickProducer(p: Producer): void {
    this.producerId    = p.id;
    this.producerLabel = `${p.producerCode} - ${p.name}`;
    this.producerSearchQuery = '';
    this.producerMatches = [];
    this.filters = { ...this.filters, producerId: p.id };
    this.refresh();
  }

  clearProducer(): void {
    this.producerId    = null;
    this.producerLabel = null;
    this.filters = { ...this.filters, producerId: undefined };
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
