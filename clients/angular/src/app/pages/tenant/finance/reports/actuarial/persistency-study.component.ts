import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  ActuarialReportsService,
  JobStatusResponse,
  PersistencyStudyJobRequest,
  PersistencyResult,
  PersistencyResultRow,
} from '../../../../../core/services/actuarial-reports.service';
import { ActuarialJobPollingService } from '../../../../../core/services/actuarial-job-polling.service';
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
 * Phase 14 §Actuarial Phase 11 — PERSISTENCY_STUDY report page. Submits
 * an async job, polls until complete, renders one A/E pivot table per
 * insurance line plus an actual-vs-expected retention curve.
 *
 * <p>Layout mirrors {@code ActuarialTriangleComponent} — filter row →
 * submit → polling → split-view render — but the result is a per-line
 * dictionary of {@link PersistencyResultRow}s rather than a chain-ladder
 * matrix. The line chart plots actual and expected against
 * checkpoint months so the eye can spot where the tenant is missing
 * their retention curve.
 */
@Component({
  selector: 'app-persistency-study',
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
  templateUrl: './persistency-study.component.html',
  styleUrls: ['./actuarial-triangle.component.scss'],
})
export class PersistencyStudyComponent implements OnInit, OnDestroy {
  readonly reportKey = 'PERSISTENCY_STUDY';
  readonly pageTitle = 'Persistency study';
  readonly pageSubtitle =
    'Actual-vs-expected retention by cohort and checkpoint. A/E ratios flag lines ' +
    'where retention is outrunning or falling short of the tenant\'s expected curve.';

  periodStart = fiveYearsAgo();
  periodEnd = todayIso();
  insuranceLine: 'HEALTH' | 'LIFE' | 'FUNERAL' | 'DISABILITY' | 'TRAVEL' | 'GROUP' | 'VEHICLE' | 'PROPERTY' | '' = '';
  reportingCurrency = '';
  checkpointsCsv = '3,6,12,24,36';

  currencies: TenantCurrencyConfig[] = [];

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
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.currencyService.listForTenant(tenantId).subscribe({
      next: (cs) => {
        this.currencies = cs.filter((c) => c.isActive);
        const def = this.currencies.find((c) => c.isDefault);
        if (def && !this.reportingCurrency) this.reportingCurrency = def.currencyCode;
      },
      error: () => { /* non-fatal */ },
    });
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
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

  onLineChange(v: string): void {
    this.insuranceLine = (v || '') as PersistencyStudyComponent['insuranceLine'];
  }
  onCurrencyChange(v: string): void { this.reportingCurrency = v; }

  submit(): void {
    if (this.submitting) return;
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    const checkpoints = this.parseCheckpoints();
    if (checkpoints === null) {
      this.errorMessage = 'Checkpoints must be a comma-separated list of positive integers.';
      return;
    }
    this.errorMessage = null;
    this.submitting = true;
    this.currentJob = null;
    this.startedAt = Date.now();

    const body: PersistencyStudyJobRequest = {
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      checkpoints,
      insuranceLine: this.insuranceLine || null,
      reportingCurrency: this.reportingCurrency || null,
    };

    this.reports.submitPersistencyStudy(body).subscribe({
      next: (resp) => {
        this.submitting = false;
        this.beginPolling(resp.jobId);
      },
      error: (err) => {
        this.submitting = false;
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to submit the persistency study';
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

  get result(): PersistencyResult | null {
    if (this.currentJob?.status !== 'completed') return null;
    return (this.currentJob.resultJson ?? null) as PersistencyResult | null;
  }

  get resultLines(): string[] {
    const r = this.result;
    return r ? Object.keys(r.per_line).sort() : [];
  }

  rowsFor(line: string): PersistencyResultRow[] {
    return this.result?.per_line[line] ?? [];
  }

  get shapeWarnings(): string[] {
    return this.currentJob?.paramsJson?.shape_warnings ?? [];
  }

  get resultWarnings(): string[] {
    return this.result?.warnings ?? [];
  }

  /**
   * Two-series line chart: actual retention % and expected retention %
   * plotted against checkpoint months, for the given line. Averaged
   * across cohorts by checkpoint so the eye can compare curves at a
   * glance — per-cohort curves are shown in the pivot table below.
   */
  retentionChartData(line: string): { name: string; series: { name: string; value: number }[] }[] {
    const rows = this.rowsFor(line);
    if (rows.length === 0) return [];
    const byCheckpoint = new Map<number, { actualSum: number; expectedSum: number; actualN: number; expectedN: number }>();
    for (const r of rows) {
      const bucket = byCheckpoint.get(r.checkpoint_months) ?? {
        actualSum: 0, expectedSum: 0, actualN: 0, expectedN: 0,
      };
      bucket.actualSum += r.actual_retention_pct;
      bucket.actualN += 1;
      if (r.expected_retention_pct != null) {
        bucket.expectedSum += r.expected_retention_pct;
        bucket.expectedN += 1;
      }
      byCheckpoint.set(r.checkpoint_months, bucket);
    }
    const months = Array.from(byCheckpoint.keys()).sort((a, b) => a - b);
    return [
      {
        name: 'Actual',
        series: months.map((m) => {
          const b = byCheckpoint.get(m)!;
          return { name: `${m}m`, value: b.actualN > 0 ? Number((b.actualSum / b.actualN * 100).toFixed(2)) : 0 };
        }),
      },
      {
        name: 'Expected',
        series: months.map((m) => {
          const b = byCheckpoint.get(m)!;
          return {
            name: `${m}m`,
            value: b.expectedN > 0 ? Number((b.expectedSum / b.expectedN * 100).toFixed(2)) : 0,
          };
        }),
      },
    ];
  }

  aeCellStyle(row: PersistencyResultRow): { [k: string]: string } {
    if (row.ae_ratio == null) return {};
    // A/E ratios near 1.0 are healthy; below flags churn, above flags over-retention.
    const t = Math.max(0, Math.min(2, row.ae_ratio));
    const hue = t < 1 ? 0 + Math.round(120 * t) : 120;
    return { 'background-color': `hsla(${hue}, 65%, 88%, 0.9)` };
  }

  private parseCheckpoints(): number[] | null {
    if (!this.checkpointsCsv.trim()) return [];
    const parts = this.checkpointsCsv.split(',').map((s) => s.trim()).filter(Boolean);
    const out: number[] = [];
    for (const p of parts) {
      const n = Number.parseInt(p, 10);
      if (Number.isNaN(n) || n <= 0) return null;
      out.push(n);
    }
    return out;
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
