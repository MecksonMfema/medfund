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
  cs('payments/advance',          'Advance Payments',          '/advance-payments',          'Provider prepayments.',                        ['finance:view_advance_payments']),
  cs('payments/advance/add',      'Create Advance Payment',    '/advance-payment',           'New provider prepayment.',                     ['finance:manage_advance_payments']),
  cs('payments/advance/:id',      'Advance Payment Detail',    '/view-advance-payment',      'Single prepayment.',                           ['finance:view_advance_payments']),
  cs('payments/ctc',              'CTC Payments',              '/ctc-payments',              'Cost-to-cure payments from finance.',           ['finance:manage_ctc_payments']),
  cs('payments/ctc/add',          'Create CTC Payment',        '/ctc-payment',               'New CTC allocation.',                          ['finance:manage_ctc_payments']),
  cs('payments/ctc/:id',          'CTC Payment Detail',        '/view-ctc-payment',          'Single CTC payment.',                          ['finance:manage_ctc_payments']),
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
  cs('banks/masca',               'Platform Bank Accounts',    '/masca-bank-accounts',       'Platform bank list.',                          ['finance:manage_banks']),
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
  cs('subledger-debtors',                 'Subledger Debtors',          '/subledger-debtors',          'Outstanding balances at journal level.', ['finance:view_subledger']),
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
  cs('reports/provider-payments',            'Provider Payments',                '/view-providers-payments',                  'Provider payment summary.',                       ['finance:view']),
  cs('reports/provider-payments/:id',        'Provider Payment Detail',          '/view-provider-payments',                   'Single provider payment list.',                   ['finance:view']),
  cs('reports/provider-payments/:id/details','Provider Payment Details',         '/view-provider-payment-details',            'Transaction-level provider payment.',             ['finance:view']),
  cs('reports/provider-payment-status',      'Provider Payment Status',          '/provider-payment-status',                  'Provider payment state aggregates.',              ['finance:view']),
  cs('reports/withheld-tax',                 'Withheld Tax Report',              '/withheld-tax',                             'Tax-withheld claims and payments.',               ['finance:view_withheld_tax']),

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
  cs('creditors/member',             'Member Creditors',             '/member-creditors',       'Member liabilities.',           ['billing:view_creditors']),
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
  cs('copayments',                   'Copayments',                   '/copayments-list',        'Member cost-share records.',                ['finance:manage_copayments']),
  cs('copayments/create',            'Create Copayments',            '/create-copayments',      'Create or adjust member cost-share.',       ['finance:manage_copayments']),

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
