import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { LineChartComponent }
  from '../../../../../shared/components/charts/line-chart/line-chart.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import {
  AiCalibrationData,
  FraudReportData,
  FraudReportParams,
  FraudReportService,
  InvestigatorProductivityRow,
  MemberTopNRow,
  ProviderTopNRow,
  TrendPoint,
} from './fraud-report.service';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';
import { defaultReportPeriodStart, defaultReportPeriodEnd } from '../shared/report-date-defaults';

/**
 * Fraud / SIU report. §B Phase 11 widens the MVP 4-tile page to a full
 * analytics surface: 6 tiles, 12-month trend line-chart, top-10 provider
 * + member tables, AI calibration with insufficient-data banner, and
 * investigator productivity (backend row-filters per Rule 4).
 */
@Component({
  selector: 'app-fraud-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, LineChartComponent, ReportBackButtonComponent],
  templateUrl: './fraud-report.component.html',
  styleUrl: './fraud-report.component.scss',
})
export class FraudReportComponent implements OnInit {
  envelope: ReportResponse<FraudReportData> | null = null;
  trendPoints: TrendPoint[] = [];
  providers: ProviderTopNRow[] = [];
  members: MemberTopNRow[] = [];
  calibration: AiCalibrationData | null = null;
  productivity: InvestigatorProductivityRow[] = [];

  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  periodStart = defaultReportPeriodStart();
  periodEnd = defaultReportPeriodEnd();
  reportingCurrency = '';

  // Anchor the trend chart at zero (matches the admin-dashboard area chart
  // grammar) and use whole-integer tick labels so months with no activity
  // don't render fractional ticks like 0.5 / 1.5.
  readonly trendYTickFormat = (value: number): string =>
    Number.isInteger(value) ? value.toLocaleString('en-US') : '';

  constructor(
    private reportService: FraudReportService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.fetch();
  }

  fetch(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.loading = true;
    const opts = this.opts();
    forkJoin({
      summary:      this.reportService.summary(opts),
      trend:        this.reportService.trend(12),
      providers:    this.reportService.topProviders(opts, 10),
      members:      this.reportService.topMembers(opts, 10),
      calibration:  this.reportService.aiCalibration(opts),
      productivity: this.reportService.investigatorProductivity(opts),
    }).subscribe({
      next: (bundle) => {
        this.envelope     = bundle.summary;
        this.trendPoints  = bundle.trend;
        this.providers    = bundle.providers;
        this.members      = bundle.members;
        this.calibration  = bundle.calibration;
        this.productivity = bundle.productivity;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load fraud report';
        this.loading = false;
      },
    });
  }

  export(): void {
    this.exporting = true;
    this.reportService.exportExcel(this.opts()).subscribe({
      next: (blob) => {
        this.exporting = false;
        this.downloadBlob(blob,
          `fraud-siu-report-${this.periodStart}-to-${this.periodEnd}.xlsx`);
      },
      error: (err) => {
        this.exporting = false;
        this.toast.error(err?.error?.detail || err?.error?.title
          || 'Failed to export XLSX');
      },
    });
  }

  perCurrencyEntries(): { currency: string; amount: string }[] {
    if (!this.envelope?.data?.savingsPerCurrency) return [];
    return Object.entries(this.envelope.data.savingsPerCurrency)
      .map(([currency, amount]) => ({ currency, amount: this.formatDecimal(amount, 2) }));
  }

  /** Whole integer with locale grouping (e.g. 1,234). */
  formatCount(value: number | string | null | undefined): string {
    if (value === null || value === undefined || value === '') return '0';
    const n = typeof value === 'number' ? value : Number(value);
    if (!Number.isFinite(n)) return String(value);
    return Math.round(n).toLocaleString('en-US');
  }

  /** Fixed-decimal amount with locale grouping (e.g. 1,234.56). */
  formatDecimal(value: number | string | null | undefined, dp = 2): string {
    if (value === null || value === undefined || value === '') return '-';
    const n = typeof value === 'number' ? value : Number(value);
    if (!Number.isFinite(n)) return String(value);
    return n.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp });
  }

  /** 4dp fraction (server-side) rendered as one-decimal percentage. */
  formatRate(value: number | string | null | undefined): string {
    if (value === null || value === undefined || value === '') return '-';
    const n = typeof value === 'number' ? value : Number(value);
    if (!Number.isFinite(n)) return String(value);
    return (n * 100).toFixed(1) + '%';
  }

  /** Line-chart series — three series, ISO month → short-month label. */
  get trendChartData(): { name: string; series: { name: string; value: number }[] }[] {
    if (this.trendPoints.length === 0) return [];
    const label = (iso: string) => {
      const d = new Date(iso + 'T00:00:00Z');
      return isNaN(d.getTime())
        ? iso
        : d.toLocaleDateString('en-GB', { month: 'short', year: '2-digit', timeZone: 'UTC' });
    };
    return [
      { name: 'Cases opened', series: this.trendPoints.map(p => ({ name: label(p.month), value: p.casesOpened })) },
      { name: 'Confirmed',    series: this.trendPoints.map(p => ({ name: label(p.month), value: p.confirmedCount })) },
      { name: 'Dismissed',    series: this.trendPoints.map(p => ({ name: label(p.month), value: p.dismissedCount })) },
    ];
  }

  /** Give the chart a stable y-max — 20% headroom above the tallest bar,
   *  minimum of 5 so an all-zero window still shows tick labels. */
  get trendYMax(): number {
    let max = 0;
    for (const p of this.trendPoints) {
      max = Math.max(max, p.casesOpened, p.confirmedCount, p.dismissedCount);
    }
    return max > 0 ? Math.ceil(max * 1.2) : 5;
  }

  nativeSummary(perCurrency: Record<string, string>): string {
    if (!perCurrency) return '-';
    const entries = Object.entries(perCurrency);
    if (entries.length === 0) return '-';
    return entries.map(([ccy, amt]) => `${ccy} ${this.formatDecimal(amt, 2)}`).join(' · ');
  }

  private opts(): FraudReportParams {
    const opts: FraudReportParams = {
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
    };
    if (this.reportingCurrency) opts.reportingCurrency = this.reportingCurrency;
    return opts;
  }

  private downloadBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }
}
