import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, Subscription, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  ActuarialReportsService,
  JobStatusResponse,
  MortalityStudyJobRequest,
  MortalityLineResult,
  MortalityResult,
  MortalityResultRow,
} from '../../../../../core/services/actuarial-reports.service';
import { ActuarialJobPollingService } from '../../../../../core/services/actuarial-job-polling.service';
import {
  ActuarialBasisTablesService,
  BasisTableMetadata,
} from '../../../../../core/services/actuarial-basis-tables.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { INSURANCE_LINES } from '../../../../../core/models/insurance-lines';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { LineChartComponent } from '../../../../../shared/components/charts/line-chart/line-chart.component';
import {
  ActuarialJobProgressComponent,
} from '../../../../../shared/components/actuarial-job-progress/actuarial-job-progress.component';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';

/**
 * Phase 14 §Actuarial Phase 13 — MORTALITY_STUDY report page. Same
 * structural shape as the lapse + persistency pages (filter → submit →
 * polling → per-line pivot table + chart) but shows actual-vs-expected
 * mortality rate per (age_band × sex). Basis + multiplier default to the
 * tenant's {@code tenant_mortality_basis} row for the line; both are
 * overridable per run for what-if analysis (basis defaults come from the
 * ai-service basis-tables catalogue).
 */
@Component({
  selector: 'app-mortality-study',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    SelectComponent,
    LineChartComponent,
    ActuarialJobProgressComponent,
    ReportBackButtonComponent,
  ],
  templateUrl: './mortality-study.component.html',
  styleUrls: ['./actuarial-triangle.component.scss'],
})
export class MortalityStudyComponent implements OnInit, OnDestroy {
  readonly reportKey = 'MORTALITY_STUDY';
  readonly pageTitle = 'Mortality study';
  readonly pageSubtitle =
    'Actual-vs-expected mortality by (age band × sex). A/E ratios above 1.0 flag ' +
    'a line where deaths are running ahead of the tenant\'s basis table.';

  periodStart = fiveYearsAgo();
  periodEnd = todayIso();
  insuranceLine: 'HEALTH' | 'LIFE' | 'FUNERAL' | 'DISABILITY' | 'TRAVEL' | 'GROUP' | 'VEHICLE' | 'PROPERTY' | '' = 'LIFE';
  reportingCurrency = '';
  basisNameOverride = '';
  multiplierOverride: number | null = null;

  currencies: TenantCurrencyConfig[] = [];
  basisTables: BasisTableMetadata[] = [];

  submitting = false;
  errorMessage: string | null = null;
  currentJob: JobStatusResponse | null = null;
  startedAt: number | null = null;
  private pollSub: Subscription | null = null;

  constructor(
    private reports: ActuarialReportsService,
    private polling: ActuarialJobPollingService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private basisService: ActuarialBasisTablesService,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (tenantId) {
      this.currencyService.listForTenant(tenantId).subscribe({
        next: (cs) => {
          this.currencies = cs.filter((c) => c.isActive);
          const def = this.currencies.find((c) => c.isDefault);
          if (def && !this.reportingCurrency) this.reportingCurrency = def.currencyCode;
        },
        error: () => { /* non-fatal */ },
      });
    }
    this.loadBasisTables().subscribe({
      next: (rows) => { this.basisTables = rows; },
      error: () => { this.basisTables = []; },
    });
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  private loadBasisTables(): Observable<BasisTableMetadata[]> {
    return this.basisService.list('mortality').pipe(
      catchError(() => of([] as BasisTableMetadata[])),
    );
  }

  get lineOptions(): SelectOption[] {
    return [
      { value: '', label: 'All lines' },
      ...INSURANCE_LINES.map((l) => ({ value: l.value, label: l.label })),
    ];
  }

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Tenant default' },
      ...this.currencies.map((c) => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  get basisOptions(): SelectOption[] {
    return [
      { value: '', label: 'Tenant configured basis' },
      ...this.basisTables.map((b) => ({ value: b.name, label: b.displayName })),
    ];
  }

  onLineChange(v: string): void {
    this.insuranceLine = (v || '') as MortalityStudyComponent['insuranceLine'];
  }
  onCurrencyChange(v: string): void { this.reportingCurrency = v; }
  onBasisChange(v: string): void { this.basisNameOverride = v; }

  submit(): void {
    if (this.submitting) return;
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    if (this.multiplierOverride != null &&
        (Number.isNaN(this.multiplierOverride) || this.multiplierOverride <= 0)) {
      this.errorMessage = 'Multiplier override must be a positive number.';
      return;
    }
    this.errorMessage = null;
    this.submitting = true;
    this.currentJob = null;
    this.startedAt = Date.now();

    const body: MortalityStudyJobRequest = {
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      insuranceLine: this.insuranceLine || null,
      basisNameOverride: this.basisNameOverride || null,
      multiplierOverride: this.multiplierOverride ?? null,
      reportingCurrency: this.reportingCurrency || null,
    };

    this.reports.submitMortalityStudy(body).subscribe({
      next: (resp) => {
        this.submitting = false;
        this.beginPolling(resp.jobId);
      },
      error: (err) => {
        this.submitting = false;
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to submit the mortality study';
      },
    });
  }

  cancelPolling(): void {
    this.pollSub?.unsubscribe();
    this.pollSub = null;
  }

  exportXlsx(): void {
    if (!this.currentJob) return;
    window.open(this.reports.exportXlsxUrl(this.currentJob.jobId), '_blank');
  }

  private beginPolling(jobId: string): void {
    this.pollSub?.unsubscribe();
    this.pollSub = this.polling.poll(jobId).subscribe({
      next: (snap) => { this.currentJob = snap; },
      error: (err) => {
        this.errorMessage = err?.message || 'Polling failed';
        this.pollSub = null;
      },
      complete: () => { this.pollSub = null; },
    });
  }

  // ── result unpacking ────────────────────────────────────────────────

  get result(): MortalityResult | null {
    if (this.currentJob?.status !== 'completed') return null;
    return (this.currentJob.resultJson ?? null) as MortalityResult | null;
  }

  get resultLines(): string[] {
    const r = this.result;
    return r ? Object.keys(r.per_line).sort() : [];
  }

  lineResult(line: string): MortalityLineResult | null {
    return this.result?.per_line[line] ?? null;
  }

  rowsFor(line: string): MortalityResultRow[] {
    return this.result?.per_line[line]?.rows ?? [];
  }

  get shapeWarnings(): string[] {
    return this.currentJob?.paramsJson?.shape_warnings ?? [];
  }

  get resultWarnings(): string[] {
    return this.result?.warnings ?? [];
  }

  /**
   * Two-series line chart: actual mortality rate vs expected mortality
   * rate plotted against age-band midpoint, averaged across sex within
   * each band so the eye can compare curves at a glance.
   */
  mortalityChartData(line: string): { name: string; series: { name: string; value: number }[] }[] {
    const rows = this.rowsFor(line);
    if (rows.length === 0) return [];
    const byBand = new Map<string, { actualSum: number; expectedSum: number; n: number }>();
    for (const r of rows) {
      const bucket = byBand.get(r.age_band) ?? { actualSum: 0, expectedSum: 0, n: 0 };
      bucket.actualSum += r.actual_mortality_rate;
      bucket.expectedSum += r.expected_mortality_rate;
      bucket.n += 1;
      byBand.set(r.age_band, bucket);
    }
    const bands = Array.from(byBand.keys()).sort();
    return [
      {
        name: 'Actual',
        series: bands.map((b) => {
          const s = byBand.get(b)!;
          return {
            name: b,
            value: s.n > 0 ? Number((s.actualSum / s.n * 1000).toFixed(3)) : 0,
          };
        }),
      },
      {
        name: 'Expected',
        series: bands.map((b) => {
          const s = byBand.get(b)!;
          return {
            name: b,
            value: s.n > 0 ? Number((s.expectedSum / s.n * 1000).toFixed(3)) : 0,
          };
        }),
      },
    ];
  }

  aeCellStyle(row: MortalityResultRow): { [k: string]: string } {
    if (row.ae_ratio == null) return {};
    // Actual > expected (A/E > 1) skews red; A/E < 1 skews green.
    // Linear ramp between hue=120 (green) and hue=0 (red) over [0, 2].
    const t = Math.max(0, Math.min(2, row.ae_ratio));
    const hue = Math.round(120 - 60 * t);
    return { 'background-color': `hsla(${hue}, 65%, 88%, 0.9)` };
  }
}

function fiveYearsAgo(): string {
  const d = new Date();
  d.setUTCFullYear(d.getUTCFullYear() - 5);
  d.setUTCDate(1);
  return d.toISOString().slice(0, 10);
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}
