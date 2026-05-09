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
  {
    path: 'accepted',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Accepted Claims', description: 'Approved claims awaiting payment.', presetStatus: 'ADJUDICATED', sidebar: 'operational' },
  },
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
  {
    path: 'rejected',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Rejected Claims', description: 'Denied claims with rejection reasons.', presetStatus: 'REJECTED', sidebar: 'operational' },
  },
  {
    path: 'staged',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Staged Claims', description: 'Claims pending additional info.', presetStatus: 'PENDING_INFO', sidebar: 'operational' },
  },
  {
    path: 'captured',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Captured Claims', description: 'Recently submitted claims awaiting verification.', presetStatus: 'SUBMITTED', sidebar: 'operational' },
  },
  {
    path: 'submit',
    canActivate: [permissionGuard(['claims:create'])],
    loadComponent: () => import('./submit/submit-claim.component').then(m => m.SubmitClaimComponent),
    data: { title: 'Submit Claim', sidebar: 'operational' },
  },
  cs('credit',         'Credit Claim',        '/create-credit-claim', 'Process refund / credit claims.',                           ['claims:create']),
  {
    path: 'search',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./lookups/member-lookup.component').then(m => m.MemberLookupComponent),
    data: { title: 'Search Member Claims', sidebar: 'operational' },
  },
  cs('tax-withheld',   'Tax-Withheld Claims', '/tax-with-held-claims','Claims with tax deduction applied.',                       ['claims:view', 'finance:view_withheld_tax']),

  // ── Drug claims ────────────────────────────────────────────────────────────
  // Drug claims share the Claim entity with claim_type='drug' — the existing
  // pending-claims-list filters by both status and claim type via route data.
  {
    path: 'drug',
    canActivate: [permissionGuard(['claims:view_drug'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Drug Claims', description: 'Pharmaceutical claims, all statuses.', presetClaimType: 'drug', sidebar: 'operational' },
  },
  {
    path: 'drug/pending',
    canActivate: [permissionGuard(['claims:view_drug'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Pending Drug Claims', description: 'Drug claims awaiting adjudication.', presetClaimType: 'drug', presetStatus: 'VERIFIED', sidebar: 'operational' },
  },
  {
    path: 'drug/rejected',
    canActivate: [permissionGuard(['claims:view_drug'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Rejected Drug Claims', description: 'Denied pharmaceutical claims.', presetClaimType: 'drug', presetStatus: 'REJECTED', sidebar: 'operational' },
  },
  {
    path: 'drug/captured',
    canActivate: [permissionGuard(['claims:view_drug'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Captured Drug Claims', description: 'Recently submitted drug claims.', presetClaimType: 'drug', presetStatus: 'SUBMITTED', sidebar: 'operational' },
  },
  {
    path: 'drug/staged',
    canActivate: [permissionGuard(['claims:view_drug'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Staged Drug Claims', description: 'Drug claims pending info.', presetClaimType: 'drug', presetStatus: 'PENDING_INFO', sidebar: 'operational' },
  },
  {
    path: 'drug/search',
    canActivate: [permissionGuard(['claims:view_drug'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Search Drug Claims', description: 'All drug claims; use the search box to filter by claim # or notes.', presetClaimType: 'drug', sidebar: 'operational' },
  },
  {
    path: 'drug/submit',
    canActivate: [permissionGuard(['claims:create_drug'])],
    loadComponent: () => import('./submit/submit-claim.component').then(m => m.SubmitClaimComponent),
    data: { title: 'Submit Drug Claim', sidebar: 'operational' },
  },
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
  // Drug pre-auths share the pre_authorizations table — tariff_code holds the
  // drug's tariff and diagnosis_code holds the indication. Same components.
  {
    path: 'drug/preauth',
    canActivate: [permissionGuard(['claims:manage_drug_preauth'])],
    loadComponent: () => import('./preauth/pre-auth-form.component').then(m => m.PreAuthFormComponent),
    data: { title: 'New Drug Pre-Auth', sidebar: 'operational' },
  },
  {
    path: 'drug/preauth/list',
    canActivate: [permissionGuard(['claims:manage_drug_preauth'])],
    loadComponent: () => import('./preauth/pre-auth-list.component').then(m => m.PreAuthListComponent),
    data: { title: 'Drug Pre-Auth List', sidebar: 'operational' },
  },

  // ── Verification ───────────────────────────────────────────────────────────
  // All three verification entrypoints land on the same component — the
  // claim-level verificationCode is generated server-side at submission, so
  // there's no separate "issue a code" flow today; the page focuses on the
  // front-desk lookup + confirm workflow.
  {
    path: 'verification-codes',
    canActivate: [permissionGuard(['claims:manage_verification_codes'])],
    loadComponent: () => import('./verification/verification-codes.component').then(m => m.VerificationCodesComponent),
    data: { title: 'Verify Claim', sidebar: 'operational' },
  },
  {
    path: 'request-code',
    canActivate: [permissionGuard(['claims:manage_verification_codes'])],
    loadComponent: () => import('./verification/verification-codes.component').then(m => m.VerificationCodesComponent),
    data: { title: 'Verify Claim', sidebar: 'operational' },
  },
  {
    path: 'preverification',
    canActivate: [permissionGuard(['claims:verify'])],
    loadComponent: () => import('./verification/verification-codes.component').then(m => m.VerificationCodesComponent),
    data: { title: 'Verify Claim', sidebar: 'operational' },
  },

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
  // Group charge lives under billing — same employer-balance lookup is
  // surfaced here so claims clerks don't have to switch sections.
  {
    path: 'group-charge',
    canActivate: [permissionGuard(['billing:view_creditors'])],
    redirectTo: '/tenant/billing/group-charge',
    pathMatch: 'full' as const,
  },
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
  {
    path: 'drugs',
    canActivate: [permissionGuard(['claims:view_drug'])],
    loadComponent: () => import('./drugs/drugs-list.component').then(m => m.DrugsListComponent),
    data: { title: 'Drug Catalogue', sidebar: 'operational' },
  },
  {
    path: 'drugs/add',
    canActivate: [permissionGuard(['claims:create_drug'])],
    loadComponent: () => import('./drugs/drug-form.component').then(m => m.DrugFormComponent),
    data: { title: 'Add Drug', sidebar: 'operational' },
  },
  {
    path: 'drugs/:id/edit',
    canActivate: [permissionGuard(['claims:create_drug'])],
    loadComponent: () => import('./drugs/drug-form.component').then(m => m.DrugFormComponent),
    data: { title: 'Edit Drug', sidebar: 'operational' },
  },

  // ── Provider registration requests ─────────────────────────────────────────
  cs('registration-requests',  'Provider Registration Requests', '/registration-requests', 'New provider applications awaiting review.', ['providers:create']),
];
