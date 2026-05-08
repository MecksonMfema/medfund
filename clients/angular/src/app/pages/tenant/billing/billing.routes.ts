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
  cs('schemes/add',                  'Add Scheme',                  '/policies/add-schemes',                  'Create a new benefit scheme.',                                    ['billing:manage_schemes']),
  cs('age-groups',                   'Age Groups',                  '/policies/view-age-group',               'Configure age-band boundaries used by pricing rules.',            ['billing:view', 'billing:manage_age_groups']),
  cs('age-groups/add',               'Add Age Group',               '/policies/add-age-group',                'Add a new age-band tier.',                                        ['billing:manage_age_groups']),
  cs('waiting-periods',              'Waiting Periods',             '/policies/waiting-period',               'Configure new-member waiting periods.',                           ['billing:manage_waiting_periods']),
  cs('scheme-change-waiting-periods','Scheme-Change Waiting Periods','/policies/scheme-change-waiting-periods','Waiting periods applied when members switch schemes.',           ['billing:manage_waiting_periods']),

  // ── Generation & statements ────────────────────────────────────────────────
  cs('generate',          'Generate Billing',     '/generate/generate-contributions', 'Run periodic contribution / invoice generation.',         ['billing:generate_billing']),
  cs('view',              'View Contributions',   '/generate/view-contributions',     'View generated contributions.',                            ['billing:view']),
  cs('statements',        'Statements',           '/generate/contribution-statement', 'Browse and export contribution statements.',              ['billing:view_statements']),

  // ── Transactions ───────────────────────────────────────────────────────────
  cs('transactions',                'Transactions',                  '/payments/view-transactions',                'All recorded transactions.',                       ['billing:view']),
  cs('transactions/add',            'Add Transaction',               '/payments/add-transactions',                 'Record a new transaction.',                        ['billing:post_transactions']),
  cs('transactions/adjustments',    'Adjustment Transactions',       '/payments/view-adjustment-transactions',     'Transactions posted as adjustments.',              ['billing:post_transactions']),
  cs('transactions/bank',           'Bank Transactions',             '/payments/view-bank-transactions',           'Bank-source transactions for reconciliation.',     ['billing:view']),
  cs('transactions/fc',             'Fund Custodian Transactions',   '/payments/view-fc-transactions',             'Custodian-routed transactions.',                   ['billing:view']),
  cs('transactions/debit-credit',   'Debit / Credit Transactions',   '/payments/view-debit-credit-transactions',   'Balance-changing entries.',                        ['billing:view']),
  cs('transactions/ctc',            'CTC Transactions',              '/payments/view-ctc-transactions',            'Cost-to-cure transaction history.',                ['billing:view']),

  // Currencies are managed in /tenant/admin/settings under the "Currencies" tab —
  // they're a tenant-admin concern, not an operational/billing day-to-day task.

  // ── Receivables (creditors / bad debts) ────────────────────────────────────
  cs('creditors',           'Creditors',           '/payments/view-creditors',         'Outstanding balances owed by members and groups.',  ['billing:view_creditors']),
  cs('bad-debts',           'Bad Debts',           '/payments/bad-debts',              'Write-offs and irrecoverable balances.',             ['billing:manage_bad_debts']),
  cs('group-charge',        'Group Charge',        '/payments/view-current-group-charge','Current employer group billing.',                  ['billing:view_creditors']),

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
