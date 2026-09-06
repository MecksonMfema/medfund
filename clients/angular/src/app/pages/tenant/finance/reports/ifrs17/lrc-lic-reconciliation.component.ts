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

type ViewMode = 'LRC' | 'LIC' | 'COMBINED';

interface CohortRow {
  cohortId: string;
  currency: string;
  model?: string;
  lrc: MovementRow;
  lic: MovementRow;
}

interface MovementRow {
  opening: number;
  newBusiness: number;
  cashFlows: number;
  revenueOrClaims: number;
  financeExpense: number;
  closing: number;
}

interface PortfolioBucket {
  portfolioId: string;
  cohorts: CohortRow[];
  lrcTotals: MovementRow;
  licTotals: MovementRow;
}

/**
 * Phase 15 §21 — IFRS 17 LRC / LIC reconciliation report page. Submits
 * the async job, polls until complete, and renders portfolio-level
 * treetables of the movement roll-forward plus a movement-waterfall
 * chart per portfolio.
 *
 * <p>View toggle switches between LRC-only, LIC-only, and combined
 * columns; the underlying data structure is the same envelope produced
 * by {@code Ifrs17JobAggregator}. Portfolio filter is deferred —
 * every submit runs against the tenant's whole portfolio catalogue
 * for the requested period (see plan §21 "What we're NOT doing").
 */
@Component({
  selector: 'app-lrc-lic-reconciliation',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    SelectComponent,
    ActuarialJobProgressComponent,
    WaterfallChartComponent,
  ],
  templateUrl: './lrc-lic-reconciliation.component.html',
  styleUrls: ['./ifrs17-reports.scss'],
})
export class LrcLicReconciliationComponent implements OnInit, OnDestroy {
  readonly reportKey = 'IFRS17_LRC_LIC_RECONCILIATION';
  readonly pageTitle = 'IFRS 17 - LRC / LIC reconciliation';
  readonly pageSubtitle =
    'Roll-forward of the liability for remaining coverage and the ' +
    'liability for incurred claims by portfolio × cohort × currency.';

  periodStart = firstOfPreviousMonth();
  periodEnd = lastOfPreviousMonth();
  reportingCurrency = '';
  currencies: TenantCurrencyConfig[] = [];

  view: ViewMode = 'COMBINED';
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
      error: () => { /* non-fatal — tenant default resolved server-side */ },
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
  setView(v: ViewMode): void { this.view = v; }

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

    this.reports.submitLrcLicReconciliation(body).subscribe({
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
      const rows: CohortRow[] = [];
      for (const [cohortId, cohortNode] of Object.entries(portfolioNode?.cohorts ?? {})) {
        for (const [currency, leaf] of Object.entries(cohortNode ?? {})) {
          const parsed = this.parseLeaf(cohortId, currency, leaf);
          if (parsed) rows.push(parsed);
        }
      }
      out.push({
        portfolioId,
        cohorts: rows,
        lrcTotals: sumMovementRows(rows.map(r => r.lrc)),
        licTotals: sumMovementRows(rows.map(r => r.lic)),
      });
    }
    return out;
  }

  private parseLeaf(cohortId: string, currency: string, leaf: Ifrs17ChunkLeaf): CohortRow | null {
    if (!leaf || leaf.status !== 'completed') return null;
    const result: any = leaf.result ?? {};
    const lrcNode = result.lrc ?? result;
    const licNode = result.lic ?? {};
    return {
      cohortId,
      currency,
      model: leaf.model,
      lrc: normaliseMovement(lrcNode, 'lrc'),
      lic: normaliseMovement(licNode, 'lic'),
    };
  }

  waterfallFor(bucket: PortfolioBucket): WaterfallMovement[] {
    // Combine both blocks' totals into a single waterfall — matches
    // the "roll-forward at a glance" the report is trying to
    // communicate. Order: opening → net cash → new business → revenue
    // released → finance expense → closing.
    const t = bucket.lrcTotals;
    const l = bucket.licTotals;
    const opening = (t.opening ?? 0) + (l.opening ?? 0);
    const closing = (t.closing ?? 0) + (l.closing ?? 0);
    return [
      { name: 'Opening', value: opening, isAnchor: true },
      { name: 'New business', value: (t.newBusiness ?? 0) + (l.newBusiness ?? 0) },
      { name: 'Cash flows', value: (t.cashFlows ?? 0) + (l.cashFlows ?? 0) },
      { name: 'Revenue / claims', value: -((t.revenueOrClaims ?? 0) + (l.revenueOrClaims ?? 0)) },
      { name: 'Finance expense', value: (t.financeExpense ?? 0) + (l.financeExpense ?? 0) },
      { name: 'Closing', value: closing, isAnchor: true },
    ];
  }

  showLrc(): boolean { return this.view === 'LRC' || this.view === 'COMBINED'; }
  showLic(): boolean { return this.view === 'LIC' || this.view === 'COMBINED'; }
}

// ── movement helpers ────────────────────────────────────────────────────

function normaliseMovement(node: any, kind: 'lrc' | 'lic'): MovementRow {
  const cashKey = kind === 'lrc' ? 'cashInflows' : 'cashOutflows';
  const revenueKey = kind === 'lrc' ? 'insuranceRevenue' : 'claimsIncurred';
  return {
    opening: numericFrom(node, 'opening'),
    newBusiness: numericFrom(node, 'newBusiness'),
    cashFlows: numericFrom(node, cashKey),
    revenueOrClaims: numericFrom(node, revenueKey),
    financeExpense: numericFrom(node, 'financeExpense'),
    closing: numericFrom(node, 'closing'),
  };
}

function numericFrom(node: any, key: string): number {
  const v = node?.[key];
  return typeof v === 'number' && Number.isFinite(v) ? v : 0;
}

function sumMovementRows(rows: MovementRow[]): MovementRow {
  return rows.reduce<MovementRow>((acc, r) => ({
    opening: acc.opening + r.opening,
    newBusiness: acc.newBusiness + r.newBusiness,
    cashFlows: acc.cashFlows + r.cashFlows,
    revenueOrClaims: acc.revenueOrClaims + r.revenueOrClaims,
    financeExpense: acc.financeExpense + r.financeExpense,
    closing: acc.closing + r.closing,
  }), {
    opening: 0, newBusiness: 0, cashFlows: 0,
    revenueOrClaims: 0, financeExpense: 0, closing: 0,
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
  d.setUTCDate(0); // last day of previous month
  return d.toISOString().slice(0, 10);
}
