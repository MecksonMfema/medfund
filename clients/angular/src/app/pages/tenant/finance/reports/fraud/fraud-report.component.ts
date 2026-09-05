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

/**
 * Fraud / SIU report. §B Phase 11 widens the MVP 4-tile page to a full
 * analytics surface: 6 tiles, 12-month trend line-chart, top-10 provider
 * + member tables, AI calibration with insufficient-data banner, and
 * investigator productivity (backend row-filters per Rule 4).
 */
@Component({
  selector: 'app-fraud-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, LineChartComponent],
  templateUrl: './fraud-report.component.html',
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

  periodStart = '';
  periodEnd = '';
  reportingCurrency = '';

  constructor(
    private reportService: FraudReportService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    const today = new Date();
    const firstOfThisMonth = new Date(today.getFullYear(), today.getMonth(), 1);
    const lastOfPrev = new Date(firstOfThisMonth.getTime() - 24 * 60 * 60 * 1000);
    const firstOfPrev = new Date(lastOfPrev.getFullYear(), lastOfPrev.getMonth(), 1);
    this.periodStart = this.iso(firstOfPrev);
    this.periodEnd = this.iso(lastOfPrev);
    this.fetch();
  }

  fetch(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.loading = true;
    const opts = this.opts();
    // Fan out all six requests in parallel — the page renders once the
    // slowest replies. Each backend endpoint is independently gated by
    // the same permission + FRAUD_SIU_REPORT toggle, so 403s propagate
    // together on the first denied call.
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
      .map(([currency, amount]) => ({ currency, amount }));
  }

  /** Line-chart series for `<app-line-chart>` — three series (opened,
   *  confirmed, dismissed). ngx-charts expects
   *  {@code [{ name, series: [{ name, value }] }]}. */
  get trendChartData(): { name: string; series: { name: string; value: number }[] }[] {
    if (this.trendPoints.length === 0) return [];
    return [
      {
        name: 'Cases opened',
        series: this.trendPoints.map(p => ({ name: p.month, value: p.casesOpened })),
      },
      {
        name: 'Confirmed',
        series: this.trendPoints.map(p => ({ name: p.month, value: p.confirmedCount })),
      },
      {
        name: 'Dismissed',
        series: this.trendPoints.map(p => ({ name: p.month, value: p.dismissedCount })),
      },
    ];
  }

  nativeSummary(perCurrency: Record<string, string>): string {
    if (!perCurrency) return '—';
    const entries = Object.entries(perCurrency);
    if (entries.length === 0) return '—';
    return entries.map(([ccy, amt]) => `${ccy} ${amt}`).join(' · ');
  }

  private opts(): FraudReportParams {
    const opts: FraudReportParams = {
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
    };
    if (this.reportingCurrency) opts.reportingCurrency = this.reportingCurrency;
    return opts;
  }

  private iso(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
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
