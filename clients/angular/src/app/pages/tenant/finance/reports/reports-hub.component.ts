import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  TenantReportConfigRow,
  TenantReportConfigService,
} from '../../../../core/services/tenant-report-config.service';
import {
  DueDateBannerRow,
  RegulatoryDueDatesService,
} from '../../../../core/services/regulatory-due-dates.service';
import { DueDateBannerComponent } from './regulatory/due-date-banner/due-date-banner.component';

interface FamilyGroup {
  family: string;
  familyLabel: string;
  reports: TenantReportConfigRow[];
}

/**
 * ReportKey → tenant-portal route. Kept in one place so the hub can
 * light up as a router link once a report page ships; anything not
 * listed here still shows in the hub as a plain label ("landing but
 * no page yet").
 *
 * <p>Detail-only reports (per-scheme/group/member drill-downs, PDF
 * exports, per-payee balance histories) are intentionally omitted:
 * they have no landing surface and can only be reached from a parent
 * summary. Prudential / tax returns without a shipped page are also
 * omitted — they render as inert labels until a page lands.
 */
const REPORT_ROUTES: Record<string, string> = {
  // ── Billing (Phase 2) ─────────────────────────────────────────────────────
  BILLING_REPORT:                  '/tenant/finance/reports/schemes',
  GROUP_BILLING_REPORT:            '/tenant/finance/reports/group-billing',

  // ── Receipts (Phase 3) ────────────────────────────────────────────────────
  RECEIPTS_REPORT:                 '/tenant/finance/reports/receipts-schemes',
  COLLECTION_RATE:                 '/tenant/finance/reports/collection-rate',

  // ── Debtors ───────────────────────────────────────────────────────────────
  AGED_DEBTORS:                    '/tenant/finance/reports/aged-debtors',
  AGED_BALANCES:                   '/tenant/finance/reports/aged-debtors',

  // ── Claims financial (Phase 4) ────────────────────────────────────────────
  CLAIMS_SUMMARY:                  '/tenant/finance/reports/claims-schemes',
  CLAIM_STATUS_LIST:               '/tenant/finance/reports/claim-status',
  CLAIMS_FREQUENCY_SEVERITY:       '/tenant/finance/reports/claims-frequency-severity',
  DENIAL_ANALYSIS:                 '/tenant/finance/reports/denial-analysis',
  HIGH_COST_CLAIMANT:              '/tenant/finance/reports/high-cost-claimants',
  PRE_AUTH_ACTIVITY:               '/tenant/finance/reports/pre-auth-activity',
  PROVIDER_NETWORK_UTILIZATION:    '/tenant/finance/reports/claims/provider-network-utilization',

  // ── Cross-service / reconciliation (Phase 5) ──────────────────────────────
  LOSS_RATIO:                      '/tenant/finance/reports/billing-vs-claims',
  MEMBER_PAYMENTS_UNIFIED:         '/tenant/finance/reports/member-payments',

  // ── Aged / cash-flow (Phase 8) ────────────────────────────────────────────
  CASH_FLOW_FORECAST_13W:          '/tenant/finance/reports/cash-flow-forecast',
  COLLECTION_RATE_TREND:           '/tenant/finance/reports/collection-rate-trend',

  // ── Policy lifecycle (Phase 13) ───────────────────────────────────────────
  POLICY_MOVEMENT:                 '/tenant/finance/reports/policy-lifecycle/movement',
  PERSISTENCY_COHORT:              '/tenant/finance/reports/policy-lifecycle/persistency-cohort',
  GROUP_CENSUS:                    '/tenant/finance/reports/policy-lifecycle/group-census',

  // ── Reinsurance (Phase 10) ────────────────────────────────────────────────
  REINSURANCE_CESSION_BORDEREAU:   '/tenant/finance/reports/reinsurance/cession-bordereau',
  REINSURANCE_RECOVERIES:          '/tenant/finance/reports/reinsurance/recoveries-bordereau',
  REINSURANCE_TREATY_UTILIZATION:  '/tenant/finance/reports/reinsurance/treaty-utilization',

  // ── Commission (Phase 11) ─────────────────────────────────────────────────
  COMMISSION_STATEMENT:            '/tenant/finance/reports/commission/statement',
  COMMISSION_CLAWBACK:             '/tenant/finance/reports/commission/clawback-register',

  // ── Underwriting (Phase 12) ───────────────────────────────────────────────
  UPR_MOVEMENT:                    '/tenant/finance/reports/underwriting/upr-movement',
  PREMIUM_REGISTER:                '/tenant/finance/reports/underwriting/premium-register',
  NEW_BUSINESS_REGISTER:           '/tenant/finance/reports/underwriting/new-business-register',
  ENDORSEMENT_REGISTER:            '/tenant/finance/reports/underwriting/endorsement-register',

  // ── Actuarial (Phase 14) ──────────────────────────────────────────────────
  IBNR_TRIANGLE:                   '/tenant/finance/reports/actuarial/ibnr-triangle',
  LOSS_TRIANGLE:                   '/tenant/finance/reports/actuarial/loss-triangle',
  PERSISTENCY_STUDY:               '/tenant/finance/reports/actuarial/persistency-study',
  LAPSE_STUDY:                     '/tenant/finance/reports/actuarial/lapse-study',
  MORTALITY_STUDY:                 '/tenant/finance/reports/actuarial/mortality-study',
  MORBIDITY_STUDY:                 '/tenant/finance/reports/actuarial/morbidity-study',

  // ── IFRS 17 (Phase 15) ────────────────────────────────────────────────────
  IFRS17_LRC_LIC_RECONCILIATION:            '/tenant/finance/reports/ifrs17/lrc-lic-reconciliation',
  IFRS17_INSURANCE_REVENUE_SERVICE_RESULT:  '/tenant/finance/reports/ifrs17/insurance-revenue-service-result',

  // ── Compliance (Phase 22-23) ──────────────────────────────────────────────
  AML_STR:                         '/tenant/finance/reports/compliance/aml-str/alerts',

  // ── Executive KPI (Phase 18) ──────────────────────────────────────────────
  // Every KPI card deep-links into the shared batch dashboard; individual
  // tile visibility is gated per-key against the tenant's report toggles.
  LOSS_RATIO_KPI:                  '/tenant/finance/reports/kpi',
  EXPENSE_RATIO:                   '/tenant/finance/reports/kpi',
  COMBINED_RATIO:                  '/tenant/finance/reports/kpi',
  CLAIMS_FREQUENCY:                '/tenant/finance/reports/kpi',
  AVERAGE_SEVERITY:                '/tenant/finance/reports/kpi',

  // ── Fraud (Phase 19) ──────────────────────────────────────────────────────
  FRAUD_SIU_REPORT:                '/tenant/finance/reports/fraud',
};

/**
 * Report keys we hide from the hub grid because the same content is
 * already reachable elsewhere in the portal — either as a dedicated
 * sidebar entry, a row action on a list page, or a drill-down from a
 * parent summary that needs an id the hub can't supply. Surfacing them
 * here just adds noise. The tenant report catalogue still owns the
 * keys (so the settings grid and scheduled-email rules stay intact);
 * this only affects hub rendering.
 */
const HIDDEN_HUB_KEYS = new Set<string>([
  // Reachable from the sidebar.
  'BAD_DEBTS',           // Billing → Bad Debts
  'DEBTORS_LIST',        // Billing → Debtors
  'INVOICE_LIST',        // Billing → Contribution Statements
  'CREDITORS',           // Finance → Creditors
  'PAYMENT_ADVICE',      // Finance → Payment Advice
  'PAYMENT_RUNS',        // Finance → Payment Runs
  'NOTES',               // Finance → Notes
  'NOTES_DEBIT',         // Finance → Notes
  'NOTES_CREDIT',        // Finance → Notes
  'NOTES_MEMO',          // Finance → Notes
  'NOTES_TAX_WITHHELD',  // Finance → Notes (tax-withheld tab)
  'ADVANCE_PAYMENTS',    // Finance → Advance Payments
  'CTC_PAYMENTS',        // Finance → CTC Payments
  'RECONCILIATIONS',     // Finance → Reconciliation

  // Per-record exports — row action on a list page.
  'INVOICE_DETAIL_PDF',  // Invoices list → row PDF export

  // Per-payee statements / balances — accessed from the member or
  // group detail page (or the corresponding creditor drill).
  'MEMBER_STATEMENT',
  'GROUP_STATEMENT',
  'MEMBER_BALANCE',
  'GROUP_BALANCE',
  'MEMBER_BALANCE_HISTORY',
  'PROVIDER_BALANCE_HISTORY',
  'ANNUAL_CAP_UTILIZATION',

  // Drill-down-only reports — reachable by clicking a row on a parent
  // summary or run detail. No standalone landing surface.
  'SCHEME_BILLING_DETAIL',
  'GROUP_BILLING_DETAIL',
  'RECEIPTS_AGGREGATE',
  'CREDITOR_PROVIDER_DETAIL',
  'CREDITOR_MEMBER_DETAIL',
  'PAYMENT_ADVICE_DETAIL',
  'PAYMENT_RUN_ITEMS',
  'PAYMENT_RUN_WORKBOOK',
]);

/**
 * Landing hub at /tenant/finance/reports. Shows every report the tenant
 * has *enabled*, grouped by family. Per-report detail pages are wired
 * as later phases build them; until then each card is informational.
 *
 * <p>Phase 0 ships the skeleton — no per-report routes exist yet, so
 * clicking a card just shows its label. Phases 2-19 replace the label
 * with a routerLink to the actual report page.
 */
@Component({
  selector: 'app-reports-hub',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent, SkeletonComponent, DueDateBannerComponent],
  templateUrl: './reports-hub.component.html',
  styleUrl: './reports-hub.component.scss',
})
export class ReportsHubComponent implements OnInit {
  loading = false;
  errorMessage: string | null = null;
  groups: FamilyGroup[] = [];
  totalEnabled = 0;
  private banners = new Map<string, DueDateBannerRow>();

  constructor(
    private reportConfig: TenantReportConfigService,
    private tenantService: TenantService,
    private dueDates: RegulatoryDueDatesService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    // Fetch banner rows in parallel with the catalogue; if the banner call
    // fails we fall through with an empty map so the hub still renders.
    forkJoin({
      rows: this.reportConfig.list(tenantId),
      banners: this.dueDates.list().pipe(catchError(() => of([] as DueDateBannerRow[]))),
    }).subscribe({
      next: ({ rows, banners }) => {
        const enabled = rows.filter(r => r.enabled && !HIDDEN_HUB_KEYS.has(r.reportKey));
        this.totalEnabled = enabled.length;
        this.groups = this.groupByFamily(enabled);
        this.banners = new Map(banners.map(b => [b.reportKey, b]));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Could not load report catalogue.';
        this.loading      = false;
      },
    });
  }

  /** Route for a given report key, or null when the page hasn't shipped
   *  yet — the hub falls back to a plain label in that case. */
  routeFor(reportKey: string): string | null {
    return REPORT_ROUTES[reportKey] ?? null;
  }

  /** Server-computed due-date banner for the report, or null when the
   *  report is not a Phase-16 regulator key for this tenant. */
  bannerFor(reportKey: string): DueDateBannerRow | null {
    return this.banners.get(reportKey) ?? null;
  }

  private groupByFamily(rows: TenantReportConfigRow[]): FamilyGroup[] {
    const byKey = new Map<string, FamilyGroup>();
    for (const row of rows) {
      const familyKey = row.family ?? 'OTHER';
      const familyLabel = row.familyLabel ?? 'Other';
      let group = byKey.get(familyKey);
      if (!group) {
        group = { family: familyKey, familyLabel, reports: [] };
        byKey.set(familyKey, group);
      }
      group.reports.push(row);
    }
    return Array.from(byKey.values());
  }
}
