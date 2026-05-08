import { Routes } from '@angular/router';
import { permissionGuard } from '../../../auth/auth.guard';
import { PermissionKey } from '../../../core/security/permissions';

const loadComingSoon = () =>
  import('../../../shared/components/coming-soon/coming-soon.component').then(m => m.ComingSoonComponent);

/**
 * Billing domain — covers the legacy "Contributions" admin (schemes,
 * generation, transactions, statements, currencies, creditors) from
 * {@code MASCA-Frontend/src/setup/routes/index.js}. Renamed in our system
 * because "Billing" is the umbrella that fits contributions + group
 * invoices + transaction posting under one section.
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

export const BILLING_ROUTES: Routes = [
  // ── Schemes (real shell — existing functional component) ──────────────────
  {
    path: 'schemes',
    canActivate: [permissionGuard(['billing:view', 'billing:manage_schemes'])],
    loadComponent: () => import('../../contributions/contributions.component').then(m => m.ContributionsComponent),
    data: { title: 'Schemes', sidebar: 'operational' },
  },
  {
    path: 'schemes/add',
    canActivate: [permissionGuard(['billing:manage_schemes'])],
    loadComponent: () => import('./schemes/scheme-form.component').then(m => m.SchemeFormComponent),
    data: { title: 'Add Scheme', sidebar: 'operational' },
  },
  {
    path: 'schemes/:id/edit',
    canActivate: [permissionGuard(['billing:manage_schemes'])],
    loadComponent: () => import('./schemes/scheme-form.component').then(m => m.SchemeFormComponent),
    data: { title: 'Edit Scheme', sidebar: 'operational' },
  },
  {
    path: 'age-groups',
    canActivate: [permissionGuard(['billing:view', 'billing:manage_age_groups'])],
    loadComponent: () => import('./age-groups/age-groups-list.component').then(m => m.AgeGroupsListComponent),
    data: { title: 'Age Groups', sidebar: 'operational' },
  },
  {
    path: 'age-groups/add',
    canActivate: [permissionGuard(['billing:manage_age_groups'])],
    loadComponent: () => import('./age-groups/age-group-form.component').then(m => m.AgeGroupFormComponent),
    data: { title: 'Add Age Group', sidebar: 'operational' },
  },
  cs('waiting-periods',              'Waiting Periods',             '/policies/waiting-period',               'Configure new-member waiting periods.',                           ['billing:manage_waiting_periods']),
  cs('scheme-change-waiting-periods','Scheme-Change Waiting Periods','/policies/scheme-change-waiting-periods','Waiting periods applied when members switch schemes.',           ['billing:manage_waiting_periods']),

  // ── Generation & statements ────────────────────────────────────────────────
  {
    path: 'generate',
    canActivate: [permissionGuard(['billing:generate_billing'])],
    loadComponent: () => import('./generate/generate-billing-wizard.component').then(m => m.GenerateBillingWizardComponent),
    data: { title: 'Generate Billing', sidebar: 'operational' },
  },
  {
    path: 'view',
    canActivate: [permissionGuard(['billing:view'])],
    loadComponent: () => import('./contributions/contributions-list.component').then(m => m.ContributionsListComponent),
    data: { title: 'View Contributions', sidebar: 'operational' },
  },
  cs('statements',        'Statements',           '/generate/contribution-statement', 'Browse and export contribution statements.',              ['billing:view_statements']),

  // ── Transactions ───────────────────────────────────────────────────────────
  {
    path: 'transactions',
    canActivate: [permissionGuard(['billing:view'])],
    loadComponent: () => import('./transactions/transactions-list.component').then(m => m.TransactionsListComponent),
    data: { title: 'Transactions', sidebar: 'operational' },
  },
  {
    path: 'transactions/add',
    canActivate: [permissionGuard(['billing:post_transactions'])],
    loadComponent: () => import('./transactions/transaction-form.component').then(m => m.TransactionFormComponent),
    data: { title: 'Add Transaction', sidebar: 'operational' },
  },
  cs('transactions/adjustments',    'Adjustment Transactions',       '/payments/view-adjustment-transactions',     'Transactions posted as adjustments.',              ['billing:post_transactions']),
  cs('transactions/bank',           'Bank Transactions',             '/payments/view-bank-transactions',           'Bank-source transactions for reconciliation.',     ['billing:view']),
  cs('transactions/fc',             'Fund Custodian Transactions',   '/payments/view-fc-transactions',             'Custodian-routed transactions.',                   ['billing:view']),
  cs('transactions/debit-credit',   'Debit / Credit Transactions',   '/payments/view-debit-credit-transactions',   'Balance-changing entries.',                        ['billing:view']),
  cs('transactions/ctc',            'CTC Transactions',              '/payments/view-ctc-transactions',            'Cost-to-cure transaction history.',                ['billing:view']),

  // Currencies are managed in /tenant/admin/settings under the "Currencies" tab —
  // they're a tenant-admin concern, not an operational/billing day-to-day task.

  // ── Receivables (creditors / bad debts) ────────────────────────────────────
  {
    path: 'creditors',
    canActivate: [permissionGuard(['billing:view_creditors'])],
    loadComponent: () => import('./creditors/creditors-list.component').then(m => m.CreditorsListComponent),
    data: { title: 'Creditors', sidebar: 'operational' },
  },
  {
    path: 'bad-debts',
    canActivate: [permissionGuard(['billing:manage_bad_debts'])],
    loadComponent: () => import('./bad-debts/bad-debts-list.component').then(m => m.BadDebtsListComponent),
    data: { title: 'Bad Debts', sidebar: 'operational' },
  },
  {
    path: 'group-charge',
    canActivate: [permissionGuard(['billing:view_creditors'])],
    loadComponent: () => import('./group-charge/group-charge.component').then(m => m.GroupChargeComponent),
    data: { title: 'Group Charge', sidebar: 'operational' },
  },

  // ── Email campaigns ────────────────────────────────────────────────────────
  cs('emails',              'Emails',              '/email/view-emails',          'Email campaign history.',                              ['admin:manage_settings']),
  cs('emails/send',         'Send Emails',         '/email/send-emails',          'Compose and dispatch a member-targeted campaign.',     ['admin:manage_settings']),
  cs('emails/senders',      'Sender Emails',       '/email/view-sender-email',    'Configured outbound sender addresses.',                ['admin:manage_settings']),
  cs('emails/senders/add',  'Add Sender Email',    '/email/add-sender-email',     'Add a verified outbound sender.',                       ['admin:manage_settings']),

  // ── Tasks ──────────────────────────────────────────────────────────────────
  cs('tasks',               'Tasks',               '/tickets/view-tasks',         'Billing-related work items.',                          ['billing:view']),
  cs('tasks/add',           'Add Task',            '/tickets/add-tasks',          'Create a new work item.',                              ['billing:view']),
  cs('tasks/assign',        'Assign Tasks',        '/tickets/assign-tasks',       'Allocate work items to billing clerks.',               ['billing:view']),
  cs('tasks/incomplete',    'Incomplete Tasks',    '/tickets/incomplete-tasks',   'Open billing work.',                                   ['billing:view']),

  // ── User management (legacy MASCA had user mgmt under contributions) ──────
  cs('users',               'Users (legacy)',      '/lookup/view-user',           'Legacy user directory — operational tenant users now live under /tenant/admin/users.', ['admin:manage_users']),
  cs('users/add',           'Add User (legacy)',   '/additions/add-user',         'Legacy add-user form — superseded by Tenant Admin → Users.',                              ['admin:manage_users']),

  // ── Group / member admin helpers ──────────────────────────────────────────
  cs('groups',              'Groups',              '/lookup/view-group',          'Employer groups participating in tenant schemes.',     ['billing:manage_groups']),
  cs('groups/add',          'Add Group',           '/additions/add-group',        'Register a new employer group.',                       ['billing:manage_groups']),
];
