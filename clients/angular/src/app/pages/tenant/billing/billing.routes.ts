import { Routes } from '@angular/router';
import { permissionGuard } from '../../../auth/auth.guard';
import { PermissionKey } from '../../../core/security/permissions';
import { schemePersonCentricGuard, tenantGroupsEnabledGuard, tenantPersonCentricGuard } from './guards/person-centric.guard';

const loadComingSoon = () =>
  import('../../../shared/components/coming-soon/coming-soon.component').then(m => m.ComingSoonComponent);

/**
 * Billing domain — covers the legacy "Contributions" admin (schemes,
 * generation, transactions, statements, currencies, creditors) from
 * {@code MASCA-Frontend/src/setup/routes/index.js}. Renamed in our system
 * because "Billing" is the umbrella that fits contributions + group
 * invoices + transaction posting under one section.
 *
 * Tenant-staff user management lives entirely under Tenant Admin — billing
 * has no business managing users; the only "users" billing touches are
 * members, which have their own section under /tenant/members.
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
    data: { title: 'Schemes', sidebar: 'operational', fullbleed: true },
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
    path: 'schemes/:schemeId/benefits',
    canActivate: [permissionGuard(['billing:view', 'billing:manage_schemes']), schemePersonCentricGuard],
    loadComponent: () => import('./benefits/benefits-list.component').then(m => m.BenefitsListComponent),
    data: { title: 'Scheme Benefits', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'schemes/:schemeId/benefits/add',
    canActivate: [permissionGuard(['billing:manage_schemes']), schemePersonCentricGuard],
    loadComponent: () => import('./benefits/benefit-form.component').then(m => m.BenefitFormComponent),
    data: { title: 'Add Benefit', sidebar: 'operational' },
  },
  {
    path: 'schemes/:schemeId/benefits/:id/edit',
    canActivate: [permissionGuard(['billing:manage_schemes']), schemePersonCentricGuard],
    loadComponent: () => import('./benefits/benefit-form.component').then(m => m.BenefitFormComponent),
    data: { title: 'Edit Benefit', sidebar: 'operational' },
  },
  {
    path: 'age-groups',
    canActivate: [permissionGuard(['billing:view', 'billing:manage_age_groups']), tenantPersonCentricGuard],
    loadComponent: () => import('./age-groups/age-groups-list.component').then(m => m.AgeGroupsListComponent),
    data: { title: 'Age Groups', sidebar: 'operational' },
  },
  {
    path: 'age-groups/add',
    canActivate: [permissionGuard(['billing:manage_age_groups']), tenantPersonCentricGuard],
    loadComponent: () => import('./age-groups/age-group-form.component').then(m => m.AgeGroupFormComponent),
    data: { title: 'Add Age Group', sidebar: 'operational' },
  },
  {
    path: 'age-groups/:id/edit',
    canActivate: [permissionGuard(['billing:manage_age_groups']), tenantPersonCentricGuard],
    loadComponent: () => import('./age-groups/age-group-form.component').then(m => m.AgeGroupFormComponent),
    data: { title: 'Edit Age Group', sidebar: 'operational' },
  },
  {
    path: 'waiting-periods',
    canActivate: [permissionGuard(['billing:view', 'billing:manage_waiting_periods'])],
    loadComponent: () => import('./waiting-periods/waiting-periods-list.component').then(m => m.WaitingPeriodsListComponent),
    data: { title: 'Waiting Periods', sidebar: 'operational' },
  },
  {
    path: 'waiting-periods/add',
    canActivate: [permissionGuard(['billing:manage_waiting_periods'])],
    loadComponent: () => import('./waiting-periods/waiting-period-form.component').then(m => m.WaitingPeriodFormComponent),
    data: { title: 'Add Waiting Period', sidebar: 'operational' },
  },
  {
    path: 'waiting-periods/:id/edit',
    canActivate: [permissionGuard(['billing:manage_waiting_periods'])],
    loadComponent: () => import('./waiting-periods/waiting-period-form.component').then(m => m.WaitingPeriodFormComponent),
    data: { title: 'Edit Waiting Period', sidebar: 'operational' },
  },
  {
    path: 'scheme-change-waiting-periods',
    canActivate: [permissionGuard(['billing:view', 'billing:manage_waiting_periods'])],
    loadComponent: () => import('./scheme-change-waiting-periods/scheme-change-waiting-periods-list.component').then(m => m.SchemeChangeWaitingPeriodsListComponent),
    data: { title: 'Scheme-Change Waiting Periods', sidebar: 'operational' },
  },
  {
    path: 'scheme-change-waiting-periods/add',
    canActivate: [permissionGuard(['billing:manage_waiting_periods'])],
    loadComponent: () => import('./scheme-change-waiting-periods/scheme-change-waiting-period-form.component').then(m => m.SchemeChangeWaitingPeriodFormComponent),
    data: { title: 'Add Scheme-Change Rule', sidebar: 'operational' },
  },
  {
    path: 'scheme-change-waiting-periods/:id/edit',
    canActivate: [permissionGuard(['billing:manage_waiting_periods'])],
    loadComponent: () => import('./scheme-change-waiting-periods/scheme-change-waiting-period-form.component').then(m => m.SchemeChangeWaitingPeriodFormComponent),
    data: { title: 'Edit Scheme-Change Rule', sidebar: 'operational' },
  },

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
  {
    path: 'statements',
    canActivate: [permissionGuard(['billing:view_statements'])],
    loadComponent: () => import('./statements/statements.component').then(m => m.StatementsComponent),
    data: { title: 'Statements', sidebar: 'operational' },
  },

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
  // Preset-filtered views of the master Transactions list. Each route loads
  // the same list component but seeds the type filter via route data.
  {
    path: 'transactions/adjustments',
    canActivate: [permissionGuard(['billing:post_transactions'])],
    loadComponent: () => import('./transactions/transactions-list.component').then(m => m.TransactionsListComponent),
    data: {
      title: 'Adjustment Transactions',
      description: 'Transactions posted as adjustments — manual debits/credits applied to contributions or balances.',
      presetTransactionType: 'ADJUSTMENT',
      sidebar: 'operational',
    },
  },
  {
    path: 'transactions/bank',
    canActivate: [permissionGuard(['billing:view'])],
    loadComponent: () => import('./transactions/transactions-list.component').then(m => m.TransactionsListComponent),
    data: {
      title: 'Bank Transactions',
      description: 'Transactions sourced from bank statements for reconciliation.',
      presetTransactionType: 'BANK',
      sidebar: 'operational',
    },
  },
  {
    path: 'transactions/fc',
    canActivate: [permissionGuard(['billing:view'])],
    loadComponent: () => import('./transactions/transactions-list.component').then(m => m.TransactionsListComponent),
    data: {
      title: 'Fund Custodian Transactions',
      description: 'Transactions routed through the fund custodian.',
      presetTransactionType: 'FC',
      sidebar: 'operational',
    },
  },
  {
    path: 'transactions/debit-credit',
    canActivate: [permissionGuard(['billing:view'])],
    loadComponent: () => import('./transactions/transactions-list.component').then(m => m.TransactionsListComponent),
    data: {
      title: 'Debit / Credit Transactions',
      description: 'Balance-changing entries — manual debits and credits not tied to a payment run.',
      presetTransactionType: 'DEBIT_CREDIT',
      sidebar: 'operational',
    },
  },
  {
    path: 'transactions/ctc',
    canActivate: [permissionGuard(['billing:view'])],
    loadComponent: () => import('./transactions/transactions-list.component').then(m => m.TransactionsListComponent),
    data: {
      title: 'CTC Transactions',
      description: 'Cost-to-cure transactions: clinical-cost transfers between schemes.',
      presetTransactionType: 'CTC',
      sidebar: 'operational',
    },
  },

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
  {
    path: 'emails',
    canActivate: [permissionGuard(['admin:manage_settings'])],
    loadComponent: () => import('./emails/campaigns-list.component').then(m => m.CampaignsListComponent),
    data: { title: 'Emails', sidebar: 'operational' },
  },
  {
    path: 'emails/send',
    canActivate: [permissionGuard(['admin:manage_settings'])],
    loadComponent: () => import('./emails/campaign-composer.component').then(m => m.CampaignComposerComponent),
    data: { title: 'Send Emails', sidebar: 'operational' },
  },
  {
    path: 'emails/senders',
    canActivate: [permissionGuard(['admin:manage_settings'])],
    loadComponent: () => import('./email-senders/email-senders-list.component').then(m => m.EmailSendersListComponent),
    data: { title: 'Sender Emails', sidebar: 'operational' },
  },
  {
    path: 'emails/senders/add',
    canActivate: [permissionGuard(['admin:manage_settings'])],
    loadComponent: () => import('./email-senders/email-sender-form.component').then(m => m.EmailSenderFormComponent),
    data: { title: 'Add Sender Email', sidebar: 'operational' },
  },
  {
    path: 'emails/senders/:id/edit',
    canActivate: [permissionGuard(['admin:manage_settings'])],
    loadComponent: () => import('./email-senders/email-sender-form.component').then(m => m.EmailSenderFormComponent),
    data: { title: 'Edit Sender Email', sidebar: 'operational' },
  },

  // ── Tasks ──────────────────────────────────────────────────────────────────
  // Deferred — will be implemented alongside the notification-service rebuild
  // (the two are tightly coupled: assigned tasks need notification dispatch).
  cs('tasks',               'Tasks',               '/tickets/view-tasks',         'Billing-related work items.',                          ['billing:view']),
  cs('tasks/add',           'Add Task',            '/tickets/add-tasks',          'Create a new work item.',                              ['billing:view']),
  cs('tasks/assign',        'Assign Tasks',        '/tickets/assign-tasks',       'Allocate work items to billing clerks.',               ['billing:view']),
  cs('tasks/incomplete',    'Incomplete Tasks',    '/tickets/incomplete-tasks',   'Open billing work.',                                   ['billing:view']),

  // ── Group / member admin helpers ──────────────────────────────────────────
  {
    path: 'groups',
    canActivate: [permissionGuard(['billing:view', 'billing:manage_groups']), tenantGroupsEnabledGuard],
    loadComponent: () => import('./groups/groups-list.component').then(m => m.GroupsListComponent),
    data: { title: 'Groups', sidebar: 'operational' },
  },
  {
    path: 'groups/add',
    canActivate: [permissionGuard(['billing:manage_groups']), tenantGroupsEnabledGuard],
    loadComponent: () => import('./groups/group-form.component').then(m => m.GroupFormComponent),
    data: { title: 'Add Group', sidebar: 'operational' },
  },
  // Legacy edit URL — the unified detail page handles editing in place.
  { path: 'groups/:id/edit', redirectTo: 'groups/:id', pathMatch: 'full' },
  {
    path: 'groups/:id',
    canActivate: [permissionGuard(['billing:view', 'billing:manage_groups']), tenantGroupsEnabledGuard],
    loadComponent: () => import('./groups/detail/group-detail.component').then(m => m.GroupDetailComponent),
    data: { title: 'Group Detail', sidebar: 'operational' },
  },
];
