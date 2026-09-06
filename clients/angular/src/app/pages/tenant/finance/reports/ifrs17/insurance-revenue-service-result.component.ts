import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  Ifrs17ChunkLeaf,
  Ifrs17ReportRequest,
  Ifrs17ResultEnvelope,
  Ifrs17ReportsService,
} from '../../../../../core/services/ifrs17-reports.service';
import {
  JobStatusResponse,
  ReportJobPollingService,
} from '../../../../../core/services/report-job-polling.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import {
  ActuarialJobProgressComponent,
} from '../../../../../shared/components/actuarial-job-progress/actuarial-job-progress.component';
import {
  WaterfallChartComponent,
  WaterfallMovement,
} from '../../../../../shared/components/charts/waterfall-chart/waterfall-chart.component';

interface RevenueRow {
  cohortId: string;
  currency: string;
  model?: string;
  insuranceRevenue: number;
  insuranceServiceExpenses: number;
  insuranceServiceResult: number;
  insuranceFinanceExpense: number;
}

interface PortfolioBucket {
  portfolioId: string;
  cohorts: RevenueRow[];
  totals: Omit<RevenueRow, 'cohortId' | 'currency' | 'model'>;
}

/**
 * Phase 15 §21 — IFRS 17 insurance revenue & service result report page.
 * Same submit / poll / render / export shape as the sibling
 * {@code LrcLicReconciliationComponent}; only the treetable columns and
 * waterfall stops differ (revenue vs revenue-less-expenses vs
 * finance-expense split).
 */
@Component({
  selector: 'app-insurance-revenue-service-result',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    SelectComponent,
    ActuarialJobProgressComponent,
    WaterfallChartComponent,
  ],
  templateUrl: './insurance-revenue-service-result.component.html',
  styleUrls: ['./ifrs17-reports.scss'],
})
export class InsuranceRevenueServiceResultComponent implements OnInit, OnDestroy {
  readonly reportKey = 'IFRS17_INSURANCE_REVENUE_SERVICE_RESULT';
  readonly pageTitle = 'IFRS 17 - insurance revenue & service result';
  readonly pageSubtitle =
    'Revenue, service expenses, service result and finance expense ' +
    'by portfolio × cohort × currency.';

  periodStart = firstOfPreviousMonth();
  periodEnd = lastOfPreviousMonth();
  reportingCurrency = '';
  currencies: TenantCurrencyConfig[] = [];

  submitting = false;
  errorMessage: string | null = null;
  currentJob: JobStatusResponse | null = null;
  startedAt: number | null = null;
  private pollSub: Subscription | null = null;

  constructor(
    private reports: Ifrs17ReportsService,
    private polling: ReportJobPollingService,
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

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Tenant default' },
      ...this.currencies.map((c) => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  onCurrencyChange(v: string): void { this.reportingCurrency = v; }

  submit(): void {
    if (this.submitting) return;
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.errorMessage = null;
    this.submitting = true;
    this.currentJob = null;
    this.startedAt = Date.now();

    const body: Ifrs17ReportRequest = {
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
      portfolioIds: null,
      reportingCurrency: this.reportingCurrency || null,
    };

    this.reports.submitInsuranceRevenueServiceResult(body).subscribe({
      next: (resp) => {
        this.submitting = false;
        this.beginPolling(resp.jobId);
      },
      error: (err) => {
        this.submitting = false;
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to submit the IFRS 17 report';
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

  // ── envelope unpacking ────────────────────────────────────────────────

  get envelope(): Ifrs17ResultEnvelope | null {
    if (this.currentJob?.status !== 'completed') return null;
    return (this.currentJob.resultJson ?? null) as Ifrs17ResultEnvelope | null;
  }

  get summary(): Ifrs17ResultEnvelope['summary'] | null {
    return this.envelope?.summary ?? null;
  }

  get portfolios(): PortfolioBucket[] {
    const env = this.envelope;
    if (!env?.portfolios) return [];
    const out: PortfolioBucket[] = [];
    for (const [portfolioId, portfolioNode] of Object.entries(env.portfolios)) {
      const rows: RevenueRow[] = [];
      for (const [cohortId, cohortNode] of Object.entries(portfolioNode?.cohorts ?? {})) {
        for (const [currency, leaf] of Object.entries(cohortNode ?? {})) {
          const parsed = this.parseLeaf(cohortId, currency, leaf);
          if (parsed) rows.push(parsed);
        }
      }
      out.push({
        portfolioId,
        cohorts: rows,
        totals: sumRows(rows),
      });
    }
    return out;
  }

  private parseLeaf(cohortId: string, currency: string, leaf: Ifrs17ChunkLeaf): RevenueRow | null {
    if (!leaf || leaf.status !== 'completed') return null;
    const result: any = leaf.result ?? {};
    const revenue = numericFrom(result, 'insuranceRevenue');
    const expenses = numericFrom(result, 'insuranceServiceExpenses');
    const finance = numericFrom(result, 'insuranceFinanceExpense');
    const serviceResult = typeof result.insuranceServiceResult === 'number'
      ? result.insuranceServiceResult
      : revenue - expenses;
    return {
      cohortId,
      currency,
      model: leaf.model,
      insuranceRevenue: revenue,
      insuranceServiceExpenses: expenses,
      insuranceServiceResult: serviceResult,
      insuranceFinanceExpense: finance,
    };
  }

  waterfallFor(bucket: PortfolioBucket): WaterfallMovement[] {
    // Revenue → less expenses → equals service result → adjust for
    // finance expense → net earnings. Anchors sit at 0-baseline
    // (start) and net earnings (end).
    const t = bucket.totals;
    const net = t.insuranceServiceResult - t.insuranceFinanceExpense;
    return [
      { name: 'Start', value: 0, isAnchor: true },
      { name: 'Revenue', value: t.insuranceRevenue },
      { name: 'Service expenses', value: -t.insuranceServiceExpenses },
      { name: 'Finance expense', value: -t.insuranceFinanceExpense },
      { name: 'Net earnings', value: net, isAnchor: true },
    ];
  }
}

// ── helpers ─────────────────────────────────────────────────────────────

function numericFrom(node: any, key: string): number {
  const v = node?.[key];
  return typeof v === 'number' && Number.isFinite(v) ? v : 0;
}

function sumRows(rows: RevenueRow[]): PortfolioBucket['totals'] {
  return rows.reduce((acc, r) => ({
    insuranceRevenue: acc.insuranceRevenue + r.insuranceRevenue,
    insuranceServiceExpenses: acc.insuranceServiceExpenses + r.insuranceServiceExpenses,
    insuranceServiceResult: acc.insuranceServiceResult + r.insuranceServiceResult,
    insuranceFinanceExpense: acc.insuranceFinanceExpense + r.insuranceFinanceExpense,
  }), {
    insuranceRevenue: 0,
    insuranceServiceExpenses: 0,
    insuranceServiceResult: 0,
    insuranceFinanceExpense: 0,
  });
}

function firstOfPreviousMonth(): string {
  const d = new Date();
  d.setUTCMonth(d.getUTCMonth() - 1);
  d.setUTCDate(1);
  return d.toISOString().slice(0, 10);
}

function lastOfPreviousMonth(): string {
  const d = new Date();
  d.setUTCDate(0);
  return d.toISOString().slice(0, 10);
}
