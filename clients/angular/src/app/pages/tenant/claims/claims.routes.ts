import { Routes } from '@angular/router';
import { permissionGuard } from '../../../auth/auth.guard';
import { PermissionKey } from '../../../core/security/permissions';

const loadComingSoon = () =>
  import('../../../shared/components/coming-soon/coming-soon.component').then(m => m.ComingSoonComponent);

/**
 * Claims domain — reproduces the route shape of the legacy
 * {@code Masca-Claims-Admin/src/App.js} so devs can map old screens to new
 * placeholders. Real implementations live in this folder; everything else
 * resolves to {@code <app-coming-soon>} for now.
 *
 * <p>Pattern: each route declares
 * {@code data: { title, description, ref, sidebar:'operational' }} and a
 * {@code permissionGuard([...])} matching the Phase 1 permission catalogue.
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

export const CLAIMS_ROUTES: Routes = [
  // ── Real shell — claims list (existing functional component) ──────────────
  {
    path: '',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('../../claims/claims.component').then(m => m.ClaimsComponent),
    data: { title: 'Claims', sidebar: 'operational' },
  },

  // ── Claims pipeline ────────────────────────────────────────────────────────
  cs('accepted',       'Accepted Claims',     '/accepted-claims',     'Approved claims awaiting payment.',                        ['claims:view']),
  {
    path: 'pending',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: {
      title: 'Pending Claims',
      description: 'Verified claims queued for adjudication.',
      presetStatus: 'VERIFIED',
      sidebar: 'operational',
    },
  },
  {
    path: ':id',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./detail/claim-detail.component').then(m => m.ClaimDetailComponent),
    data: { title: 'Claim Detail', sidebar: 'operational' },
  },
  cs('rejected',       'Rejected Claims',     '/rejected-claims',     'Denied claims with rejection reasons.',                    ['claims:view']),
  cs('staged',         'Staged Claims',       '/staged-claims',       'Draft claims being prepared.',                              ['claims:view']),
  cs('captured',       'Captured Claims',     '/captured-claims',     'Recently submitted claims.',                                ['claims:view']),
  {
    path: 'submit',
    canActivate: [permissionGuard(['claims:create'])],
    loadComponent: () => import('./submit/submit-claim.component').then(m => m.SubmitClaimComponent),
    data: { title: 'Submit Claim', sidebar: 'operational' },
  },
  cs('credit',         'Credit Claim',        '/create-credit-claim', 'Process refund / credit claims.',                           ['claims:create']),
  cs('search',         'Search Member Claims',     '/search-member-claims', 'Find claims by member ID or name.',                  ['claims:view']),
  cs('tax-withheld',   'Tax-Withheld Claims', '/tax-with-held-claims','Claims with tax deduction applied.',                       ['claims:view', 'finance:view_withheld_tax']),

  // ── Drug claims ────────────────────────────────────────────────────────────
  cs('drug',                'Drug Claims',          '/drug-claims',                'Approved pharmaceutical claims.',           ['claims:view_drug']),
  cs('drug/pending',        'Pending Drug Claims',  '/pending-drug-claims',        'Drug claims under review.',                  ['claims:view_drug']),
  cs('drug/rejected',       'Rejected Drug Claims', '/rejected-drug-claims',       'Denied pharmaceutical claims.',              ['claims:view_drug']),
  cs('drug/staged',         'Staged Drug Claims',   '/staged-drug-claims',         'Draft drug submissions.',                    ['claims:view_drug']),
  cs('drug/captured',       'Captured Drug Claims', '/captured-drug-claims',       'Recently added drug claims.',                ['claims:view_drug']),
  cs('drug/submit',         'Submit Drug Claim',    '/submit-drug-claim',          'New pharmaceutical claim entry.',            ['claims:create_drug']),
  cs('drug/search',         'Search Drug Claims',   '/search-member-drug-claims',  'Find drug claims by member.',                ['claims:view_drug']),
  cs('drug/tax-withheld',   'Tax-Withheld Drug Claims', '/tax-with-held-drugclaims', 'Drug claims with tax deduction.',          ['claims:view_drug', 'finance:view_withheld_tax']),

  // ── Pre-authorisation ──────────────────────────────────────────────────────
  {
    path: 'preauth',
    canActivate: [permissionGuard(['claims:manage_preauth'])],
    loadComponent: () => import('./preauth/pre-auth-list.component').then(m => m.PreAuthListComponent),
    data: { title: 'Pre-Authorizations', sidebar: 'operational' },
  },
  {
    path: 'preauth/new',
    canActivate: [permissionGuard(['claims:manage_preauth'])],
    loadComponent: () => import('./preauth/pre-auth-form.component').then(m => m.PreAuthFormComponent),
    data: { title: 'New Pre-Auth', sidebar: 'operational' },
  },
  {
    path: 'preauth/:id',
    canActivate: [permissionGuard(['claims:manage_preauth'])],
    loadComponent: () => import('./preauth/pre-auth-detail.component').then(m => m.PreAuthDetailComponent),
    data: { title: 'Pre-Auth Detail', sidebar: 'operational' },
  },
  cs('drug/preauth',             'Drug Pre-Auth',        '/pre-authorization-drug-claims',  'Request pharmaceutical approval before claim.',           ['claims:manage_drug_preauth']),
  cs('drug/preauth/list',        'Drug Pre-Auth List',   '/drug-pre-auth-list',             'View all drug pre-auth requests.',                        ['claims:manage_drug_preauth']),

  // ── Verification ───────────────────────────────────────────────────────────
  cs('verification-codes',     'Verification Codes',         '/verification-codes',          'Manage one-time verification codes.',            ['claims:manage_verification_codes']),
  cs('request-code',           'Request Verification Code',  '/request-verification-code',   'Issue a new verification code.',                  ['claims:manage_verification_codes']),
  cs('preverification',        'Pre-Verification',           '/submit-preverification',      'Validate a claim before submission.',             ['claims:verify']),

  // ── Configuration (tariffs, modifiers, rejection reasons, ICD) ────────────
  {
    path: 'tariffs',
    canActivate: [permissionGuard(['claims:manage_tariffs'])],
    loadComponent: () => import('./tariffs/tariff-schedules-list.component').then(m => m.TariffSchedulesListComponent),
    data: { title: 'Tariff Schedules', sidebar: 'operational' },
  },
  {
    path: 'tariffs/add',
    canActivate: [permissionGuard(['claims:manage_tariffs'])],
    loadComponent: () => import('./tariffs/tariff-schedule-form.component').then(m => m.TariffScheduleFormComponent),
    data: { title: 'New Tariff Schedule', sidebar: 'operational' },
  },
  {
    path: 'tariffs/:scheduleId/codes',
    canActivate: [permissionGuard(['claims:manage_tariffs'])],
    loadComponent: () => import('./tariffs/tariff-codes-list.component').then(m => m.TariffCodesListComponent),
    data: { title: 'Tariff Codes', sidebar: 'operational' },
  },
  {
    path: 'modifiers',
    canActivate: [permissionGuard(['claims:manage_modifiers'])],
    loadComponent: () => import('./modifiers/modifiers-list.component').then(m => m.ModifiersListComponent),
    data: { title: 'Tariff Modifiers', sidebar: 'operational' },
  },
  {
    path: 'rejection-reasons',
    canActivate: [permissionGuard(['claims:manage_rejection_reasons'])],
    loadComponent: () => import('./rejection-reasons/rejection-reasons-list.component').then(m => m.RejectionReasonsListComponent),
    data: { title: 'Rejection Reasons', sidebar: 'operational' },
  },
  {
    path: 'rejection-reasons/add',
    canActivate: [permissionGuard(['claims:manage_rejection_reasons'])],
    loadComponent: () => import('./rejection-reasons/rejection-reason-form.component').then(m => m.RejectionReasonFormComponent),
    data: { title: 'Add Rejection Reason', sidebar: 'operational' },
  },
  {
    path: 'rejection-reasons/:id/edit',
    canActivate: [permissionGuard(['claims:manage_rejection_reasons'])],
    loadComponent: () => import('./rejection-reasons/rejection-reason-form.component').then(m => m.RejectionReasonFormComponent),
    data: { title: 'Edit Rejection Reason', sidebar: 'operational' },
  },
  {
    path: 'icd-codes',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./icd-codes/icd-codes-search.component').then(m => m.IcdCodesSearchComponent),
    data: { title: 'ICD-10 Codes', sidebar: 'operational' },
  },
  cs('scheme-limits',          'Scheme Limits',       '/scheme-limits',          'View per-scheme benefit caps.',                ['claims:view']),

  // ── Lookups ────────────────────────────────────────────────────────────────
  {
    path: 'provider-lookup',
    canActivate: [permissionGuard(['providers:view'])],
    loadComponent: () => import('./lookups/provider-lookup.component').then(m => m.ProviderLookupComponent),
    data: { title: 'Provider Lookup', sidebar: 'operational' },
  },
  {
    path: 'member-lookup',
    canActivate: [permissionGuard(['members:view'])],
    loadComponent: () => import('./lookups/member-lookup.component').then(m => m.MemberLookupComponent),
    data: { title: 'Member Lookup', sidebar: 'operational' },
  },
  {
    path: 'tariff-lookup',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./lookups/tariff-lookup.component').then(m => m.TariffLookupComponent),
    data: { title: 'Tariff Lookup', sidebar: 'operational' },
  },
  cs('group-charge',           'Group Charge Lookup',  '/group-charge-lookup', 'Look up employer group billing.',    ['billing:view_creditors']),
  cs('special-waivers',        'Special Waivers',      '/special-waivers',     'Override benefit limits per member.',['members:manage_waivers']),

  // ── Tasks ──────────────────────────────────────────────────────────────────
  cs('tasks/incomplete',       'Incomplete Tasks',         '/incomplete-tasks',                'Open work items.',                       ['claims:manage_tasks']),
  cs('tasks/complete',         'Completed Tasks',          '/complete-tasks',                  'Finished work items.',                   ['claims:manage_tasks']),
  cs('tasks/add',              'Add Task',                 '/add-task',                        'Create a new work item.',                ['claims:manage_tasks']),
  cs('tasks/assign',           'Assign Claims',            '/assign-claims',                   'Allocate claims to staff.',              ['claims:assign']),
  cs('tasks/assign/drug',      'Assign Drug Claims',       '/assign-drug-claims',              'Allocate drug claims to staff.',         ['claims:assign']),
  cs('tasks/user/incomplete',  'My Incomplete Tasks',      '/user-incomplete-tasks',           'My open assignments.',                   ['claims:manage_tasks']),
  cs('tasks/user/claims',      'My Claim Tasks',           '/claims-user-incomplete-tasks',    'Staff-specific claim tasks.',            ['claims:manage_tasks']),
  cs('tasks/user/drugs',       'My Drug Tasks',            '/drugs-user-incomplete-tasks',     'Staff-specific drug tasks.',             ['claims:manage_tasks']),
  cs('tasks/user/complete',    'My Completed Tasks',       '/user-complete-tasks',             'Staff finished work.',                   ['claims:manage_tasks']),

  // ── CTC payments (Cost-To-Cure) ───────────────────────────────────────────
  cs('ctc/pending',            'Pending CTC Payments',     '/pending-ctc-payments',     'Cost-to-cure allocations awaiting commit.',     ['claims:view_ctc_payments']),
  cs('ctc/committed',          'Committed CTC Payments',   '/committed-ctc-payments',   'Allocated cost-to-cure payments.',              ['claims:view_ctc_payments']),
  cs('ctc/add',                'Add CTC Payment',          '/add-ctc-payment',          'Create a cost-to-cure allocation.',             ['claims:commit_ctc_payment']),
  cs('ctc/auto',               'Auto CTC Payments',        '/auto-ctc-payments',        'Automated CTC allocation rules.',                ['claims:view_ctc_payments']),

  // ── Drug inventory ─────────────────────────────────────────────────────────
  cs('drugs',                  'Drug Inventory',           '/view-drugs',                'Browse the drug catalogue.',           ['claims:view_drug']),
  cs('drugs/add',              'Add Drug',                 '/add-drug',                  'Add a drug to the catalogue.',         ['claims:create_drug']),

  // ── Provider registration requests ─────────────────────────────────────────
  cs('registration-requests',  'Provider Registration Requests', '/registration-requests', 'New provider applications awaiting review.', ['providers:create']),
];
