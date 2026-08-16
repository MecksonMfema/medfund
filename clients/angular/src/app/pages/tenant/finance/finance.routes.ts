import { Routes } from '@angular/router';
import { permissionGuard } from '../../../auth/auth.guard';
import { PermissionKey } from '../../../core/security/permissions';

const loadComingSoon = () =>
  import('../../../shared/components/coming-soon/coming-soon.component').then(m => m.ComingSoonComponent);

/**
 * Finance domain — covers payment runs, payments, receipts, banks,
 * adjustments, reconciliation, reports, creditors, copayments. Mirrors
 * {@code Masca-Finance-Typescript/src/App.tsx}.
 */
const cs = (
  path: string,
  title: string,
  ref: string,
  description: string,
  perms: PermissionKey[],
): import('@angular/router').Route => ({
  path,
  canActivate: [permissionGuard(perms)],
  loadComponent: loadComingSoon,
  data: { title, description, sidebar: 'operational', ref },
});

export const FINANCE_ROUTES: Routes = [
  // ── Index — no finance dashboard; land the user on Payment Runs, the
  // natural first surface in this section. ──────────────────────────────────
  { path: '', pathMatch: 'full', redirectTo: 'runs' },

  // ── Payment runs ───────────────────────────────────────────────────────────
  {
    path: 'runs',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./runs/payment-runs-list.component').then(m => m.PaymentRunsListComponent),
    data: { title: 'Payment Runs', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'runs/generate',
    canActivate: [permissionGuard(['finance:create_payment_run'])],
    loadComponent: () => import('./runs/payment-run-generate.component').then(m => m.PaymentRunGenerateComponent),
    data: { title: 'Generate Payment Run', sidebar: 'operational' },
  },
  {
    path: 'runs/current',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./runs/payment-runs-list.component').then(m => m.PaymentRunsListComponent),
    data: {
      title: 'Current Payment Run',
      description: 'Drafts awaiting approval and execution.',
      presetStatus: 'draft',
      sidebar: 'operational',
      fullbleed: true,
    },
  },
  {
    path: 'runs/:id',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./runs/payment-run-detail.component').then(m => m.PaymentRunDetailComponent),
    data: { title: 'Payment Run Detail', sidebar: 'operational' },
  },

  // ── Payments ───────────────────────────────────────────────────────────────
  {
    path: 'payments',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./payments/payments-list.component').then(m => m.PaymentsListComponent),
    data: { title: 'Payments', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'payments/pending',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./payments/payments-list.component').then(m => m.PaymentsListComponent),
    data: {
      title: 'Pending Payments',
      description: 'Payments awaiting execution.',
      presetStatus: 'pending',
      sidebar: 'operational',
      fullbleed: true,
    },
  },
  {
    path: 'payments/advance',
    canActivate: [permissionGuard(['finance:view_advance_payments'])],
    loadComponent: () => import('./advance/advance-payments-list.component').then(m => m.AdvancePaymentsListComponent),
    data: { title: 'Advance Payments', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'payments/advance/add',
    canActivate: [permissionGuard(['finance:manage_advance_payments'])],
    loadComponent: () => import('./advance/advance-payment-form.component').then(m => m.AdvancePaymentFormComponent),
    data: { title: 'Record Advance Payment', sidebar: 'operational' },
  },
  {
    path: 'payments/advance/:id',
    canActivate: [permissionGuard(['finance:view_advance_payments'])],
    loadComponent: () => import('./advance/advance-payment-detail.component').then(m => m.AdvancePaymentDetailComponent),
    data: { title: 'Advance Payment Detail', sidebar: 'operational' },
  },
  {
    path: 'payments/ctc',
    canActivate: [permissionGuard(['finance:manage_ctc_payments'])],
    loadComponent: () => import('./ctc/ctc-payments-list.component').then(m => m.CtcPaymentsListComponent),
    data: { title: 'CTC Payments', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'payments/ctc/add',
    canActivate: [permissionGuard(['finance:manage_ctc_payments'])],
    loadComponent: () => import('./ctc/ctc-payment-form.component').then(m => m.CtcPaymentFormComponent),
    data: { title: 'Create CTC Payment', sidebar: 'operational' },
  },
  {
    path: 'payments/ctc/:id',
    canActivate: [permissionGuard(['finance:manage_ctc_payments'])],
    loadComponent: () => import('./ctc/ctc-payment-detail.component').then(m => m.CtcPaymentDetailComponent),
    data: { title: 'CTC Payment Detail', sidebar: 'operational' },
  },
  {
    path: 'payments/:id',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./payments/payment-detail.component').then(m => m.PaymentDetailComponent),
    data: { title: 'Payment Detail', sidebar: 'operational' },
  },

  // ── Receipts ──────────────────────────────────────────────────────────────
  // Receipts are inbound contribution transactions (legacy MASCA name). The
  // tenant transactions catalogue doesn't seed a dedicated RECEIPT type, so
  // the receipts surface is the existing billing transactions list with a
  // descriptive title. /receipts/report stays ComingSoon — an analytics
  // dashboard would need a new aggregate endpoint in contributions-service.
  { path: 'receipts',        pathMatch: 'full', redirectTo: '/tenant/billing/transactions' },
  { path: 'receipts/groups', pathMatch: 'full', redirectTo: '/tenant/billing/transactions' },
  // Phase 3 (G38) — retire the receipts-report ComingSoon stub in favour of
  // the real per-group receipts surface (the treasurer's default entry).
  { path: 'receipts/report',        pathMatch: 'full', redirectTo: 'reports/receipts-groups' },

  // ── Notes (V074: unified debit / credit / memo notes) ────────────────────
  // Single surface with filter chips — the split debit-notes / credit-notes
  // pages were consolidated. Filtering by direction is a chip on the list
  // (default: all directions).
  {
    path: 'notes',
    canActivate: [permissionGuard(['finance.notes:read'])],
    loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
    data: { title: 'Notes', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'notes/new',
    canActivate: [permissionGuard(['finance.notes:write'])],
    loadComponent: () => import('./notes/note-form.component').then(m => m.NoteFormComponent),
    data: { title: 'New Note', sidebar: 'operational' },
  },
  {
    path: 'notes/tax-withheld',
    canActivate: [permissionGuard(['finance.notes:read'])],
    loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
    data: {
      title: 'Tax-Withheld Notes',
      description: 'Notes recording withheld tax against provider payouts.',
      presetNoteType: 'TAX_WITHHELD',
      sidebar: 'operational',
      fullbleed: true,
    },
  },
  {
    path: 'notes/:id',
    canActivate: [permissionGuard(['finance.notes:read'])],
    loadComponent: () => import('./notes/note-detail.component').then(m => m.NoteDetailComponent),
    data: { title: 'Note Detail', sidebar: 'operational' },
  },

  // ── Legacy redirects (retained one release; V074) ────────────────────────
  { path: 'adjustments',              pathMatch: 'full', redirectTo: 'notes' },
  { path: 'adjustments/new',          pathMatch: 'full', redirectTo: 'notes/new' },
  { path: 'adjustments/tax-withheld', pathMatch: 'full', redirectTo: 'notes/tax-withheld' },
  { path: 'adjustments/:id',          pathMatch: 'full', redirectTo: 'notes/:id' },
  { path: 'debit-notes',              pathMatch: 'full', redirectTo: 'notes' },
  { path: 'debit-notes/new',          pathMatch: 'full', redirectTo: 'notes/new' },
  { path: 'credit-notes',             pathMatch: 'full', redirectTo: 'notes' },
  { path: 'credit-notes/new',         pathMatch: 'full', redirectTo: 'notes/new' },

  // ── Reconciliation ────────────────────────────────────────────────────────
  {
    path: 'reconciliations',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./reconciliations/reconciliations-list.component').then(m => m.ReconciliationsListComponent),
    data: { title: 'Bank Reconciliation', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'reconciliations/new',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./reconciliations/reconciliation-form.component').then(m => m.ReconciliationFormComponent),
    data: { title: 'Record Statement', sidebar: 'operational' },
  },
  {
    path: 'subledger-debtors',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () => import('./creditors/creditors-list.component').then(m => m.CreditorsListComponent),
    data: {
      title: 'Subledger Debtors',
      description: 'Outstanding provider balances at journal level.',
      sidebar: 'operational',
    },
  },
  // Phase 5 (G51) — retire the billing-to-claims ComingSoon stubs in favour
  // of the real loss-ratio report (reports/billing-vs-claims). The detail
  // stub had no consumer; both redirect to the report.
  { path: 'billing-to-claims',     pathMatch: 'full', redirectTo: 'reports/billing-vs-claims' },
  { path: 'billing-to-claims/:id', pathMatch: 'full', redirectTo: 'reports/billing-vs-claims' },
  // Phase 3 (G38) — retire the receipts-to-billing ComingSoon stubs in
  // favour of the real collection-rate report. The detail stub had no
  // consumer; the primary redirects to /reports/collection-rate.
  { path: 'receipts-to-billing',     pathMatch: 'full', redirectTo: 'reports/collection-rate' },
  { path: 'receipts-to-billing/:id', pathMatch: 'full', redirectTo: 'reports/collection-rate' },

  // ── Reports ───────────────────────────────────────────────────────────────
  {
    path: 'reports',
    pathMatch: 'full',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./reports/reports-hub.component').then(m => m.ReportsHubComponent),
    data: { title: 'Reports', description: 'Every enabled report, grouped by family.', sidebar: 'operational' },
  },
  // Phase 2 billing family — replaces the ComingSoon stubs for
  // /reports/schemes and /reports/group-billing. Detail routes stay
  // ComingSoon until Phase 4 (claims-financial) needs the drill target.
  {
    path: 'reports/schemes',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/billing/scheme-billing-report.component').then(m => m.SchemeBillingReportComponent),
    data: {
      title: 'Billing report — per scheme',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'BILLING_REPORT',
    },
  },
  cs('reports/scheme/:id',                   'Scheme Report Detail',             '/view-scheme-report',                       'Single scheme analytics.',                        ['finance:view_subledger']),
  {
    path: 'reports/group-billing',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/billing/group-billing-report.component').then(m => m.GroupBillingReportComponent),
    data: {
      title: 'Billing report — per group',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'GROUP_BILLING_REPORT',
    },
  },
  // Phase 3 §8 — per-member billing (owed-back to Phase 2).
  {
    path: 'reports/member-billing',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/member-billing/member-billing-report.component').then(m => m.MemberBillingReportComponent),
    data: {
      title: 'Billing report — per member',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'BILLING_REPORT',
    },
  },
  cs('reports/member-billing/:id', 'Member Billing Detail', '/view-member-billing',
    'Per-member billing detail.', ['finance:view_subledger']),
  // Phase 3 receipts family — replaces the receipts/report + receipts-to-billing
  // ComingSoon stubs. Detail route is a single component with a dimension input.
  {
    path: 'reports/receipts-schemes',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/receipts/scheme-receipts-report.component').then(m => m.SchemeReceiptsReportComponent),
    data: {
      title: 'Receipts — per scheme',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'RECEIPTS_REPORT',
    },
  },
  {
    path: 'reports/receipts-groups',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/receipts/group-receipts-report.component').then(m => m.GroupReceiptsReportComponent),
    data: {
      title: 'Receipts — per group',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'RECEIPTS_REPORT',
    },
  },
  {
    path: 'reports/receipts-members',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/receipts/member-receipts-report.component').then(m => m.MemberReceiptsReportComponent),
    data: {
      title: 'Receipts — per member',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'RECEIPTS_REPORT',
    },
  },
  {
    path: 'reports/receipts-scheme/:id',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/receipts/receipts-detail.component').then(m => m.ReceiptsDetailComponent),
    data: {
      title: 'Scheme receipts detail',
      dimension: 'scheme',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'RECEIPTS_AGGREGATE',
    },
  },
  {
    path: 'reports/receipts-group/:id',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/receipts/receipts-detail.component').then(m => m.ReceiptsDetailComponent),
    data: {
      title: 'Group receipts detail',
      dimension: 'group',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'RECEIPTS_AGGREGATE',
    },
  },
  {
    path: 'reports/receipts-member/:id',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/receipts/receipts-detail.component').then(m => m.ReceiptsDetailComponent),
    data: {
      title: 'Member receipts detail',
      dimension: 'member',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'RECEIPTS_AGGREGATE',
    },
  },
  {
    path: 'reports/collection-rate',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/collection-rate/collection-rate-report.component').then(m => m.CollectionRateReportComponent),
    data: {
      title: 'Collection rate',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'COLLECTION_RATE',
    },
  },
  // Phase 8 — 13-week cash-flow forecast + portfolio-level collection-rate
  // trend + aged-debtors report page (replacing the debtors-report stub,
  // D8-2/D8-8 — route key AGED_BALANCES gates this page's API).
  {
    path: 'reports/cash-flow-forecast',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/cash-flow-forecast/cash-flow-forecast.component').then(m => m.CashFlowForecastComponent),
    data: {
      title: 'Cash-flow forecast',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CASH_FLOW_FORECAST_13W',
    },
  },
  {
    path: 'reports/collection-rate-trend',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/collection-rate-trend/collection-rate-trend.component').then(m => m.CollectionRateTrendComponent),
    data: {
      title: 'Collection-rate trend',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'COLLECTION_RATE_TREND',
    },
  },
  {
    path: 'reports/aged-debtors',
    canActivate: [permissionGuard(['finance:view_debtors'])],
    loadComponent: () =>
      import('./reports/aged-debtors/aged-debtors.component').then(m => m.AgedDebtorsComponent),
    data: {
      title: 'Aged debtors',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'AGED_BALANCES',
    },
  },
  // Phase 4 §A claims-financial family — per-scheme / per-provider summaries,
  // high-cost claimants, and pre-auth activity.
  {
    path: 'reports/claims-schemes',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/scheme-claims-report.component').then(m => m.SchemeClaimsReportComponent),
    data: {
      title: 'Claims — per scheme',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIMS_SUMMARY',
    },
  },
  {
    path: 'reports/claims-providers',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/provider-claims-report.component').then(m => m.ProviderClaimsReportComponent),
    data: {
      title: 'Claims — per provider',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIMS_SUMMARY',
    },
  },
  {
    path: 'reports/claims-scheme/:id',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/claims-detail.component').then(m => m.ClaimsDetailComponent),
    data: {
      title: 'Scheme claims detail',
      dimension: 'scheme',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIMS_SUMMARY',
    },
  },
  {
    path: 'reports/claims-provider/:id',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/claims-detail.component').then(m => m.ClaimsDetailComponent),
    data: {
      title: 'Provider claims detail',
      dimension: 'provider',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIMS_SUMMARY',
    },
  },
  {
    path: 'reports/high-cost-claimants',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/high-cost-claimants-report.component').then(m => m.HighCostClaimantsReportComponent),
    data: {
      title: 'High-cost claimants',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'HIGH_COST_CLAIMANT',
    },
  },
  {
    path: 'reports/pre-auth-activity',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/pre-auth-activity-report.component').then(m => m.PreAuthActivityReportComponent),
    data: {
      title: 'Pre-auth activity',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'PRE_AUTH_ACTIVITY',
    },
  },
  // Phase 4 §B — group / member legs of CLAIMS_SUMMARY, the status matrix,
  // denial analysis, and frequency & severity.
  {
    path: 'reports/claims-groups',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/group-claims-report.component').then(m => m.GroupClaimsReportComponent),
    data: {
      title: 'Claims — per group',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIMS_SUMMARY',
    },
  },
  {
    path: 'reports/claims-members',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/member-claims-report.component').then(m => m.MemberClaimsReportComponent),
    data: {
      title: 'Claims — per member',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIMS_SUMMARY',
    },
  },
  {
    path: 'reports/claims-group/:id',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/claims-detail.component').then(m => m.ClaimsDetailComponent),
    data: {
      title: 'Group claims detail',
      dimension: 'group',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIMS_SUMMARY',
    },
  },
  {
    path: 'reports/claims-member/:id',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/claims-detail.component').then(m => m.ClaimsDetailComponent),
    data: {
      title: 'Member claims detail',
      dimension: 'member',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIMS_SUMMARY',
    },
  },
  {
    path: 'reports/claim-status',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/claim-status-matrix.component').then(m => m.ClaimStatusMatrixComponent),
    data: {
      title: 'Claim status matrix',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIM_STATUS_LIST',
    },
  },
  {
    path: 'reports/denial-analysis',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/denial-analysis-report.component').then(m => m.DenialAnalysisReportComponent),
    data: {
      title: 'Denial analysis',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'DENIAL_ANALYSIS',
    },
  },
  {
    path: 'reports/claims-frequency-severity',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/claims/frequency-severity-report.component').then(m => m.FrequencySeverityReportComponent),
    data: {
      title: 'Claims frequency & severity',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'CLAIMS_FREQUENCY_SEVERITY',
    },
  },
  // Stub retirement per G51 — target (reports/claim-status) now exists so the redirect is safe.
  { path: 'reports/claims-status', pathMatch: 'full', redirectTo: 'reports/claim-status' },
  cs('reports/group-schemes',                'Group Schemes Report',             '/group-schemes-report',                     'Employer benefit schemes.',                       ['finance:view_subledger']),
  // Phase 5 (G51) — replace the group-billing-to-claims ComingSoon stub with
  // the real loss-ratio report. The detail stub had no consumer; it
  // redirects to the report.
  {
    path: 'reports/billing-vs-claims',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/loss-ratio/loss-ratio-report.component').then(m => m.LossRatioReportComponent),
    data: {
      title: 'Loss ratio',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'LOSS_RATIO',
    },
  },
  { path: 'reports/group-billing-to-claims/:id', pathMatch: 'full', redirectTo: 'reports/billing-vs-claims' },
  cs('reports/group-notes',                  'Group Notes Report',               '/group-notes-report',                       'Employer-level notes.',                           ['finance.notes:read']),
  cs('reports/group-notes/:id',              'Group Note Detail',                '/group-note-detail',                        'Single note.',                                    ['finance.notes:read']),
  // Legacy /reports/group-adjustments redirects
  { path: 'reports/group-adjustments',     pathMatch: 'full', redirectTo: 'reports/group-notes' },
  { path: 'reports/group-adjustments/:id', pathMatch: 'full', redirectTo: 'reports/group-notes/:id' },
  // Phase 5 (G51 + D5) — replace the member-payments ComingSoon stub with the
  // real unified member-payments report. Permission tightened to
  // finance:view_subledger (matches the other subledger reports). The detail
  // stubs below stay ComingSoon.
  {
    path: 'reports/member-payments',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/member-payments/member-payments-report.component').then(m => m.MemberPaymentsReportComponent),
    data: {
      title: 'Member payments',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'MEMBER_PAYMENTS_UNIFIED',
    },
  },
  cs('reports/member-payments/:id',          'Member Payment Detail',            '/view-member-payments',                     'Single member payment list.',                     ['finance:view']),
  cs('reports/member-payments/:id/details',  'Member Payment Details',           '/view-member-payment-details',              'Transaction-level member payment.',               ['finance:view']),
  cs('reports/member-payment-status',        'Member Payment Status',            '/member-payment-status',                    'Member payment state aggregates.',                ['finance:view']),
  // Phase 6 (D6-7) — per-payee balance history (frozen at each executed run).
  // Entry point is the "Balance history" button on the creditors detail pages;
  // the reports hub stays as-is (no routerLinks for any report yet).
  {
    path: 'reports/balance-history/provider/:id',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/balance-history/provider-balance-history.component').then(m => m.ProviderBalanceHistoryComponent),
    data: {
      title: 'Provider balance history',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'PROVIDER_BALANCE_HISTORY',
    },
  },
  {
    path: 'reports/balance-history/member/:id',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./reports/balance-history/member-balance-history.component').then(m => m.MemberBalanceHistoryComponent),
    data: {
      title: 'Member balance history',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'MEMBER_BALANCE_HISTORY',
    },
  },
  {
    path: 'reports/provider-payments',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./payments/payments-list.component').then(m => m.PaymentsListComponent),
    data: {
      title: 'Provider Payments',
      description: 'Provider payouts. Filter by status, currency, or search by reference.',
      sidebar: 'operational',
      fullbleed: true,
    },
  },
  cs('reports/provider-payments/:id',        'Provider Payment Detail',          '/view-provider-payments',                   'Single provider payment list.',                   ['finance:view']),
  cs('reports/provider-payments/:id/details','Provider Payment Details',         '/view-provider-payment-details',            'Transaction-level provider payment.',             ['finance:view']),
  {
    path: 'reports/provider-payment-status',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./payments/payments-list.component').then(m => m.PaymentsListComponent),
    data: {
      title: 'Provider Payment Status',
      description: 'Provider payouts grouped by state. Use the status filter to drill into pending / paid / cancelled.',
      sidebar: 'operational',
      fullbleed: true,
    },
  },
  // committed-payments alias — paid-only history under a memorable name.
  {
    path: 'reports/committed-payments',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./payments/payments-list.component').then(m => m.PaymentsListComponent),
    data: {
      title: 'Committed Payments',
      description: 'Payouts that have settled. Filtered to status=paid.',
      presetStatus: 'paid',
      sidebar: 'operational',
      fullbleed: true,
    },
  },
  {
    path: 'reports/withheld-tax',
    canActivate: [permissionGuard(['finance:view_withheld_tax'])],
    loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
    data: {
      title: 'Withheld Tax Report',
      description: 'Tax withheld from provider payouts. Filtered to TAX_WITHHELD notes.',
      presetNoteType: 'TAX_WITHHELD',
      sidebar: 'operational',
      fullbleed: true,
    },
  },

  // ── Creditors ─────────────────────────────────────────────────────────────
  // Unified surface — providers + members side by side. Old
  // /creditors/provider and /creditors/member landing routes are
  // deliberately removed (aggressive cutover, G7c). Detail routes stay
  // typed so a link can point at a specific subject.
  {
    path: 'creditors',
    canActivate: [permissionGuard(['finance:view_creditors'])],
    loadComponent: () => import('./creditors/creditors-list.component').then(m => m.CreditorsListComponent),
    data: { title: 'Creditors', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'creditors/provider/:id',
    canActivate: [permissionGuard(['finance:view_creditors'])],
    loadComponent: () => import('./creditors/provider-balance-detail.component').then(m => m.ProviderBalanceDetailComponent),
    data: { title: 'Provider Balance', sidebar: 'operational' },
  },
  {
    path: 'creditors/member/:id',
    canActivate: [permissionGuard(['finance:view_creditors'])],
    loadComponent: () => import('./creditors/member-balance-detail.component').then(m => m.MemberBalanceDetailComponent),
    data: { title: 'Member Balance', sidebar: 'operational' },
  },

  // ── Currencies ────────────────────────────────────────────────────────────
  cs('currencies',                   'Currencies',                   '/view-currency',          'Configured currency / FX pairs.',          ['billing:view_currencies']),
  cs('currencies/add',               'Add Currency',                 '/add-currency',           'Add a new currency / FX pair.',            ['billing:manage_currencies']),

  // ── Payment advice ────────────────────────────────────────────────────────
  {
    path: 'advice',
    canActivate: [permissionGuard(['finance:view_payment_advice'])],
    loadComponent: () => import('./advice/payment-advice.component').then(m => m.PaymentAdviceComponent),
    data: { title: 'Payment Advice', sidebar: 'operational', fullbleed: true },
  },
  cs('advice/member',                'Member Payment Advice',        '/member-payment-advice',  'Notifications to members.',                 ['finance:view_payment_advice']),
  {
    path: 'advices/:id',
    canActivate: [permissionGuard(['finance:view_payment_advice'])],
    loadComponent: () => import('./advices/payment-advice-detail.component').then(m => m.PaymentAdviceDetailComponent),
    data: { title: 'Payment Advice Detail', sidebar: 'operational' },
  },

  // ── Cost-share receipts (renamed from Copayments, V079) ──────────────────
  // Money-in path: member paid the tenant against their cost-share balance.
  // Transaction-type filter renamed COPAYMENT → COPAYMENT_RECEIPT in the
  // same PR that shipped V079 (G13 rename).
  {
    path: 'copayments',
    canActivate: [permissionGuard(['finance:manage_copayments'])],
    loadComponent: () => import('../billing/transactions/transactions-list.component').then(m => m.TransactionsListComponent),
    data: {
      title: 'Cost-share receipts',
      description: 'Member cost-share receipts recorded against adjudicated claims.',
      presetTransactionType: 'COPAYMENT_RECEIPT',
      sidebar: 'operational',
      fullbleed: true,
    },
  },
  {
    path: 'copayments/create',
    canActivate: [permissionGuard(['finance:manage_copayments'])],
    loadComponent: () => import('../billing/transactions/transaction-form.component').then(m => m.TransactionFormComponent),
    data: { title: 'Record Cost-share Receipt', sidebar: 'operational' },
  },

  // ── Member liabilities (V078, Phase 4 copayments) ────────────────────────
  // Money-out path: what the fund has billed the member for their cost share
  // on each adjudicated claim. Reads from finance-service's
  // member_cost_share_liability + member_cost_share_settlement tables.
  {
    path: 'member-liabilities',
    canActivate: [permissionGuard(['finance:view_member_liabilities'])],
    loadComponent: () => import('./liabilities/member-liabilities-list.component').then(m => m.MemberLiabilitiesListComponent),
    data: { title: 'Member Liabilities', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'member-liabilities/:id',
    canActivate: [permissionGuard(['finance:view_member_liabilities'])],
    loadComponent: () => import('./liabilities/member-liability-detail.component').then(m => m.MemberLiabilityDetailComponent),
    data: { title: 'Liability Detail', sidebar: 'operational' },
  },

];
