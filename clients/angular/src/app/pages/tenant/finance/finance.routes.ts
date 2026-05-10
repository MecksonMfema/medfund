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
  // ── Dashboard (index) ──────────────────────────────────────────────────────
  {
    path: '',
    pathMatch: 'full',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./dashboard/finance-dashboard.component').then(m => m.FinanceDashboardComponent),
    data: { title: 'Finance', sidebar: 'operational' },
  },

  // ── Payment runs ───────────────────────────────────────────────────────────
  {
    path: 'runs',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./runs/payment-runs-list.component').then(m => m.PaymentRunsListComponent),
    data: { title: 'Payment Runs', sidebar: 'operational' },
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
    data: { title: 'Payments', sidebar: 'operational' },
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
    },
  },
  {
    path: 'payments/advance',
    canActivate: [permissionGuard(['finance:view_advance_payments'])],
    loadComponent: () => import('./advance/advance-payments-list.component').then(m => m.AdvancePaymentsListComponent),
    data: { title: 'Advance Payments', sidebar: 'operational' },
  },
  {
    path: 'payments/advance/add',
    canActivate: [permissionGuard(['finance:manage_advance_payments'])],
    loadComponent: () => import('./advance/advance-payment-form.component').then(m => m.AdvancePaymentFormComponent),
    data: { title: 'Record Advance Payment', sidebar: 'operational' },
  },
  {
    path: 'payments/ctc',
    canActivate: [permissionGuard(['finance:manage_ctc_payments'])],
    loadComponent: () => import('./ctc/ctc-payments-list.component').then(m => m.CtcPaymentsListComponent),
    data: { title: 'CTC Payments', sidebar: 'operational' },
  },
  {
    path: 'payments/ctc/add',
    canActivate: [permissionGuard(['finance:manage_ctc_payments'])],
    loadComponent: () => import('./ctc/ctc-payment-form.component').then(m => m.CtcPaymentFormComponent),
    data: { title: 'Create CTC Payment', sidebar: 'operational' },
  },
  {
    path: 'payments/:id',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./payments/payment-detail.component').then(m => m.PaymentDetailComponent),
    data: { title: 'Payment Detail', sidebar: 'operational' },
  },

  // ── Receipts ──────────────────────────────────────────────────────────────
  cs('receipts',                  'Receipts',                  '/receipts',                  'Captured payment receipts.',                   ['finance:manage_receipts']),
  cs('receipts/groups',           'Group Receipts',            '/groups-receipts-list',      'Employer group receipts.',                     ['finance:manage_receipts']),
  cs('receipts/report',           'Receipts Report',           '/view-receipts-report',      'Receipts analytics dashboard.',                ['finance:manage_receipts']),

  // ── Banks ──────────────────────────────────────────────────────────────────
  cs('banks',                     'Banks',                     '/bank-management',           'Bank account management.',                     ['finance:manage_banks']),
  {
    path: 'banks/masca',
    canActivate: [permissionGuard(['finance:manage_banks'])],
    loadComponent: () => import('./banks/masca-banks.component').then(m => m.MascaBanksComponent),
    data: { title: 'Platform Bank Accounts', sidebar: 'operational' },
  },
  cs('banks/edit',                'Edit Bank Account',         '/edit-bank',                 'Update bank details.',                         ['finance:manage_banks']),

  // ── Adjustments ───────────────────────────────────────────────────────────
  {
    path: 'adjustments',
    canActivate: [permissionGuard(['finance:post_adjustments'])],
    loadComponent: () => import('./adjustments/adjustments-list.component').then(m => m.AdjustmentsListComponent),
    data: { title: 'Adjustments', sidebar: 'operational' },
  },
  {
    path: 'adjustments/new',
    canActivate: [permissionGuard(['finance:post_adjustments'])],
    loadComponent: () => import('./adjustments/adjustment-form.component').then(m => m.AdjustmentFormComponent),
    data: { title: 'New Adjustment', sidebar: 'operational' },
  },
  {
    path: 'adjustments/tax-withheld',
    canActivate: [permissionGuard(['finance:post_adjustments'])],
    loadComponent: () => import('./adjustments/adjustments-list.component').then(m => m.AdjustmentsListComponent),
    data: {
      title: 'Tax-Withheld Adjustments',
      description: 'Adjustments recording withheld tax against provider payouts.',
      presetType: 'TAX_WITHHELD',
      sidebar: 'operational',
    },
  },
  {
    path: 'adjustments/:id',
    canActivate: [permissionGuard(['finance:post_adjustments'])],
    loadComponent: () => import('./adjustments/adjustment-detail.component').then(m => m.AdjustmentDetailComponent),
    data: { title: 'Adjustment Detail', sidebar: 'operational' },
  },
  {
    path: 'debit-notes',
    canActivate: [permissionGuard(['finance:post_adjustments'])],
    loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
    data: { title: 'Debit Notes', mode: 'debit', sidebar: 'operational' },
  },
  {
    path: 'credit-notes',
    canActivate: [permissionGuard(['finance:post_adjustments'])],
    loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
    data: { title: 'Credit Notes', mode: 'credit', sidebar: 'operational' },
  },

  // ── Reconciliation ────────────────────────────────────────────────────────
  {
    path: 'reconciliations',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./reconciliations/reconciliations-list.component').then(m => m.ReconciliationsListComponent),
    data: { title: 'Bank Reconciliation', sidebar: 'operational' },
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
  cs('debtors-report',                    'Debtors Report',             '/view-debtors-report',        'Aged-debtors analytics.',                ['finance:view_debtors']),
  cs('billing-to-claims',                 'Billing → Claims',           '/billing-to-claims',          'Reconcile billing runs against claims.', ['finance:manage_billing_reconcile']),
  cs('billing-to-claims/:id',             'Billing → Claims Detail',    '/view-billing-to-claims',     'Single reconciliation entry.',          ['finance:manage_billing_reconcile']),
  cs('receipts-to-billing',               'Receipts → Billing',         '/receipts-to-billing',        'Match receipts against billing rows.',   ['finance:manage_billing_reconcile']),
  cs('receipts-to-billing/:id',           'Receipts → Billing Detail',  '/view-receipts-to-billing',   'Single match detail.',                   ['finance:manage_billing_reconcile']),

  // ── Reports ───────────────────────────────────────────────────────────────
  cs('reports',                              'Reports',                          '/reports',                                  'Finance reporting hub.',                          ['finance:view']),
  cs('reports/schemes',                      'Schemes Report',                   '/schemes-list',                             'Per-scheme aggregated metrics.',                  ['finance:view_subledger']),
  cs('reports/scheme/:id',                   'Scheme Report Detail',             '/view-scheme-report',                       'Single scheme analytics.',                        ['finance:view_subledger']),
  cs('reports/group-billing',                'Group Billing Report',             '/group-billing-list',                       'Employer group billing aggregates.',              ['finance:view_subledger']),
  cs('reports/group-schemes',                'Group Schemes Report',             '/group-schemes-report',                     'Employer benefit schemes.',                       ['finance:view_subledger']),
  cs('reports/group-billing-to-claims',      'Group Billing → Claims',           '/group-billing-to-claims-list',             'Employer-level billing-to-claim reconcile.',      ['finance:manage_billing_reconcile']),
  cs('reports/group-billing-to-claims/:id',  'Group Billing → Claims Detail',    '/group-billing-to-claims-detail',           'Single employer reconciliation.',                 ['finance:manage_billing_reconcile']),
  cs('reports/group-adjustments',            'Group Adjustments Report',         '/group-adjustments-report',                 'Employer-level adjustments.',                     ['finance:post_adjustments']),
  cs('reports/group-adjustments/:id',        'Group Adjustment Detail',          '/group-adjustment-detail',                  'Single adjustment.',                              ['finance:post_adjustments']),
  cs('reports/claims-status',                'Claims Status Report',             '/claims-status-report',                     'Claim state analytics.',                          ['finance:view']),
  cs('reports/member-payments',              'Member Payments',                  '/view-members-payments',                    'Member payment summary.',                         ['finance:view']),
  cs('reports/member-payments/:id',          'Member Payment Detail',            '/view-member-payments',                     'Single member payment list.',                     ['finance:view']),
  cs('reports/member-payments/:id/details',  'Member Payment Details',           '/view-member-payment-details',              'Transaction-level member payment.',               ['finance:view']),
  cs('reports/member-payment-status',        'Member Payment Status',            '/member-payment-status',                    'Member payment state aggregates.',                ['finance:view']),
  {
    path: 'reports/provider-payments',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () => import('./payments/payments-list.component').then(m => m.PaymentsListComponent),
    data: {
      title: 'Provider Payments',
      description: 'Provider payouts. Filter by status, currency, or search by reference.',
      sidebar: 'operational',
    },
  },
  cs('reports/provider-payments/:id',        'Provider Payment Detail',          '/view-provider-payments',                   'Single provider payment list.',                   ['finance:view']),
  cs('reports/provider-payments/:id/details','Provider Payment Details',         '/view-provider-payment-details',            'Transaction-level provider payment.',             ['finance:view']),
  cs('reports/provider-payment-status',      'Provider Payment Status',          '/provider-payment-status',                  'Provider payment state aggregates.',              ['finance:view']),
  {
    path: 'reports/withheld-tax',
    canActivate: [permissionGuard(['finance:view_withheld_tax'])],
    loadComponent: () => import('./adjustments/adjustments-list.component').then(m => m.AdjustmentsListComponent),
    data: {
      title: 'Withheld Tax Report',
      description: 'Tax withheld from provider payouts. Filtered to TAX_WITHHELD adjustments.',
      presetType: 'TAX_WITHHELD',
      sidebar: 'operational',
    },
  },

  // ── Creditors ─────────────────────────────────────────────────────────────
  {
    path: 'creditors/provider',
    canActivate: [permissionGuard(['billing:view_creditors'])],
    loadComponent: () => import('./creditors/creditors-list.component').then(m => m.CreditorsListComponent),
    data: { title: 'Provider Creditors', sidebar: 'operational' },
  },
  {
    path: 'creditors/provider/:id',
    canActivate: [permissionGuard(['billing:view_creditors'])],
    loadComponent: () => import('./creditors/provider-balance-detail.component').then(m => m.ProviderBalanceDetailComponent),
    data: { title: 'Provider Balance', sidebar: 'operational' },
  },
  {
    path: 'creditors/member',
    canActivate: [permissionGuard(['billing:view_creditors'])],
    loadComponent: () => import('./creditors/creditors-list.component').then(m => m.CreditorsListComponent),
    data: {
      title: 'Member Creditors',
      description: 'Member liabilities. Currently shares the provider list view; member-balance backend lands in a follow-up slice.',
      sidebar: 'operational',
    },
  },
  cs('creditors/:id',                'Creditor Detail',              '/view-creditor',          'Single creditor.',              ['billing:view_creditors']),

  // ── Currencies ────────────────────────────────────────────────────────────
  cs('currencies',                   'Currencies',                   '/view-currency',          'Configured currency / FX pairs.',          ['billing:view_currencies']),
  cs('currencies/add',               'Add Currency',                 '/add-currency',           'Add a new currency / FX pair.',            ['billing:manage_currencies']),

  // ── Payment advice ────────────────────────────────────────────────────────
  {
    path: 'advice',
    canActivate: [permissionGuard(['finance:view_payment_advice'])],
    loadComponent: () => import('./advice/payment-advice.component').then(m => m.PaymentAdviceComponent),
    data: { title: 'Payment Advice', sidebar: 'operational' },
  },
  cs('advice/member',                'Member Payment Advice',        '/member-payment-advice',  'Notifications to members.',                 ['finance:view_payment_advice']),

  // ── Copayments ────────────────────────────────────────────────────────────
  // Copayments are contributions-service transactions with type=COPAYMENT.
  // Route-data presets reuse the billing transaction list / form so the
  // operator lands on the same surface they already know from billing.
  {
    path: 'copayments',
    canActivate: [permissionGuard(['finance:manage_copayments'])],
    loadComponent: () => import('../billing/transactions/transactions-list.component').then(m => m.TransactionsListComponent),
    data: {
      title: 'Copayments',
      description: 'Member cost-share receipts. Filtered to COPAYMENT transaction type.',
      presetTransactionType: 'COPAYMENT',
      sidebar: 'operational',
    },
  },
  {
    path: 'copayments/create',
    canActivate: [permissionGuard(['finance:manage_copayments'])],
    loadComponent: () => import('../billing/transactions/transaction-form.component').then(m => m.TransactionFormComponent),
    data: { title: 'Record Copayment', sidebar: 'operational' },
  },

  // ── Tasks ─────────────────────────────────────────────────────────────────
  cs('tasks/incomplete',             'Incomplete Tasks',             '/incomplete-tasks',         'Open finance work items.',                ['finance:view']),
  cs('tasks/complete',               'Completed Tasks',              '/complete-tasks',           'Finished finance work.',                   ['finance:view']),
  cs('tasks/clerk/incomplete',       'Clerk Incomplete Tasks',       '/clerk-incomplete-tasks',   'Clerk-specific open tasks.',               ['finance:view']),
  cs('tasks/clerk/complete',         'Clerk Completed Tasks',        '/clerk-complete-tasks',     'Clerk-specific finished tasks.',           ['finance:view']),
  cs('tasks/unassigned',             'Unassigned Tasks',             '/unassigned-tasks',         'Tasks without an owner.',                  ['finance:view']),
  cs('tasks/add',                    'Add Task',                     '/add-task',                 'Create a finance task.',                   ['finance:view']),
  cs('tasks/reassign',               'Reassign Task',                '/re-assign-task',           'Change a task\'s owner.',                  ['finance:view']),
  cs('tasks/revoked',                'Revoked Tasks',                '/revoked-tasks',            'Cancelled tasks.',                         ['finance:view']),
];
