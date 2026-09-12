import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  ActuarialReportsService,
  ChainLadderResult,
  type JobStatusResponse,
  TriangleJobRequest,
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
 * Phase 14 §Actuarial Phase 10 shared shell for the two triangle reports
 * (IBNR + LOSS_TRIANGLE). The two pages have identical filters + polling +
 * split-view rendering — only the submit endpoint + page label differ, so
 * the concrete IBNR / LOSS components thin-wrap this one with an
 * {@code @Input()} for the report key.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Filter row: period picker (defaults to last 20 quarters), insurance
 *       line, shape (paid/incurred/reported), grain (month/quarter/year),
 *       reporting currency, LDF method.</li>
 *   <li>Submit → returns jobId → hand to {@link ActuarialJobPollingService}
 *       for the polling loop. Progress component owns the visual state.</li>
 *   <li>On {@code completed}, render a two-pane split — cumulative triangle
 *       matrix (left) + LDF-by-development-period line chart (right).</li>
 *   <li>Export button opens the XLSX URL in a new tab so the browser
 *       streams the download rather than the Angular HttpClient.</li>
 * </ul>
 */
@Component({
  selector: 'app-actuarial-triangle',
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
  templateUrl: './actuarial-triangle.component.html',
  styleUrl: './actuarial-triangle.component.scss',
})
export class ActuarialTriangleComponent implements OnInit, OnDestroy {
  @Input({ required: true }) reportKey!: 'IBNR_TRIANGLE' | 'LOSS_TRIANGLE';
  @Input({ required: true }) pageTitle!: string;
  @Input({ required: true }) pageSubtitle!: string;

  periodStart = twentyQuartersAgo();
  periodEnd   = todayIso();
  insuranceLine: 'HEALTH' | 'LIFE' | 'FUNERAL' | 'DISABILITY' | 'TRAVEL' | 'GROUP' | 'VEHICLE' | 'PROPERTY' | '' = '';
  shape: 'paid' | 'incurred' | 'reported' = 'paid';
  grain: 'month' | 'quarter' | 'year' = 'quarter';
  reportingCurrency = '';
  ldfMethod: 'volume' | 'simple' | '5yr' = 'volume';

  currencies: TenantCurrencyConfig[] = [];

  submitting = false;
  errorMessage: string | null = null;
  currentJob: JobStatusResponse | null = null;
  startedAt: number | null = null;
  triangleParams: TriangleJobRequest | null = null;
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

  get shapeOptions(): SelectOption[] {
    return [
      { value: 'paid',     label: 'Paid' },
      { value: 'incurred', label: 'Incurred' },
      { value: 'reported', label: 'Reported' },
    ];
  }

  get grainOptions(): SelectOption[] {
    return [
      { value: 'month',   label: 'Month' },
      { value: 'quarter', label: 'Quarter' },
      { value: 'year',    label: 'Year' },
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

  get ldfMethodOptions(): SelectOption[] {
    return [
      { value: 'volume', label: 'Volume-weighted (default)' },
      { value: 'simple', label: 'Simple average' },
      { value: '5yr',    label: '5-year weighted' },
    ];
  }

  onLineChange(v: string): void {
    this.insuranceLine = (v || '') as ActuarialTriangleComponent['insuranceLine'];
  }
  onShapeChange(v: string): void { this.shape = v as 'paid' | 'incurred' | 'reported'; }
  onGrainChange(v: string): void { this.grain = v as 'month' | 'quarter' | 'year'; }
  onCurrencyChange(v: string): void { this.reportingCurrency = v; }
  onLdfMethodChange(v: string): void { this.ldfMethod = v as 'volume' | 'simple' | '5yr'; }

  submit(): void {
    if (this.submitting) return;
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.errorMessage = null;
    this.submitting = true;
    this.currentJob = null;
    this.startedAt  = Date.now();

    const body: TriangleJobRequest = {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      insuranceLine: this.insuranceLine || null,
      shape: this.shape,
      grain: this.grain,
      reportingCurrency: this.reportingCurrency || null,
      ldfMethod: this.ldfMethod || null,
    };
    this.triangleParams = body;

    const submit$ = this.reportKey === 'IBNR_TRIANGLE'
      ? this.reports.submitIbnr(body)
      : this.reports.submitLoss(body);

    submit$.subscribe({
      next: (resp) => {
        this.submitting = false;
        this.beginPolling(resp.jobId);
      },
      error: (err) => {
        this.submitting = false;
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to submit the actuarial job';
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
      next:  (snap) => { this.currentJob = snap; },
      error: (err) => {
        this.errorMessage = err?.message || 'Polling failed';
        this.pollSub = null;
      },
      complete: () => { this.pollSub = null; },
    });
  }

  // ── result unpacking + display helpers ─────────────────────────────────

  get result(): ChainLadderResult | null {
    if (this.currentJob?.status !== 'completed') return null;
    return (this.currentJob.resultJson ?? null) as ChainLadderResult | null;
  }

  get accidentPeriods(): string[] {
    return this.currentJob?.paramsJson?.triangle?.accident_periods ?? [];
  }

  get developmentPeriods(): string[] {
    return this.currentJob?.paramsJson?.triangle?.development_periods ?? [];
  }

  get triangleCells(): (number | null)[][] {
    return this.currentJob?.paramsJson?.triangle?.cells ?? [];
  }

  get shapeWarnings(): string[] {
    return this.currentJob?.paramsJson?.shape_warnings ?? [];
  }

  /** ngx-charts multi-series payload — one series per accident cohort. */
  get ldfChartData(): { name: string; series: { name: string; value: number }[] }[] {
    const r = this.result;
    if (!r || !r.ldfs?.length) return [];
    return [{
      name: 'Volume-weighted LDF',
      series: r.ldfs.map((v, i) => ({ name: `Dev ${i + 1}`, value: Number(v.toFixed(4)) })),
    }];
  }

  /**
   * Colour intensity for the triangle matrix — green (low) → red (high).
   * Applied to the {@code background} of each numeric cell so the eye can
   * spot heat concentration at a glance. Empty cells (upper right of the
   * triangle) render blank with no colour.
   */
  cellStyle(value: number | null, allValues: number[]): { [k: string]: string } {
    if (value == null) return {};
    if (allValues.length === 0) return {};
    const min = Math.min(...allValues);
    const max = Math.max(...allValues);
    const span = max - min || 1;
    const t = (value - min) / span;
    const hue = 120 - Math.round(120 * t);
    return { 'background-color': `hsla(${hue}, 65%, 88%, 0.9)` };
  }

  get flatCellValues(): number[] {
    return this.triangleCells.flat().filter((v): v is number => typeof v === 'number');
  }
}

function twentyQuartersAgo(): string {
  const d = new Date();
  d.setUTCMonth(d.getUTCMonth() - 60);
  d.setUTCDate(1);
  return d.toISOString().slice(0, 10);
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}
