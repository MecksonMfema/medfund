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
  cs('receipts/report',           'Receipts Report',           '/view-receipts-report',      'Receipts analytics dashboard.',                ['finance:manage_receipts']),

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
  cs('reports/group-notes',                  'Group Notes Report',               '/group-notes-report',                       'Employer-level notes.',                           ['finance.notes:read']),
  cs('reports/group-notes/:id',              'Group Note Detail',                '/group-note-detail',                        'Single note.',                                    ['finance.notes:read']),
  // Legacy /reports/group-adjustments redirects
  { path: 'reports/group-adjustments',     pathMatch: 'full', redirectTo: 'reports/group-notes' },
  { path: 'reports/group-adjustments/:id', pathMatch: 'full', redirectTo: 'reports/group-notes/:id' },
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

];
