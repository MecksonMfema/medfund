import { Routes } from '@angular/router';
import { permissionGuard } from '../../../auth/auth.guard';
import { PermissionKey } from '../../../core/security/permissions';
import { tenantPreauthGuard } from './preauth/preauth-line.guard';

const loadComingSoon = () =>
  import('../../../shared/components/coming-soon/coming-soon.component').then(m => m.ComingSoonComponent);

const loadRoadmap = () =>
  import('../../../shared/components/roadmap-placeholder/roadmap-placeholder.component').then(m => m.RoadmapPlaceholderComponent);

/**
 * Route factory for a feature whose UI is scaffolded but which is
 * blocked on a backend endpoint that hasn't shipped. Renders the
 * {@link RoadmapPlaceholderComponent} with a "will do / meanwhile"
 * layout.
 */
const rm = (
  path: string,
  title: string,
  description: string,
  perms: PermissionKey[],
  roadmap: { blockedBy?: string; willDo?: string[]; currentAlternative?: { text: string; links?: { label: string; path: string }[] } },
): import('@angular/router').Route => ({
  path,
  canActivate: [permissionGuard(perms)],
  loadComponent: loadRoadmap,
  data: { title, description, sidebar: 'operational', roadmap },
});

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
    data: { title: 'Claims', sidebar: 'operational', fullbleed: true },
  },

  // ── Claims pipeline ────────────────────────────────────────────────────────
  {
    path: 'accepted',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Accepted Claims', description: 'Approved claims awaiting payment.', presetStatus: 'ADJUDICATED', sidebar: 'operational', fullbleed: true },
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
      fullbleed: true,
    },
  },
  {
    path: 'rejected',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Rejected Claims', description: 'Denied claims with rejection reasons.', presetStatus: 'REJECTED', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'staged',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Staged Claims', description: 'Claims pending additional info.', presetStatus: 'PENDING_INFO', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'captured',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./pending/pending-claims-list.component').then(m => m.PendingClaimsListComponent),
    data: { title: 'Captured Claims', description: 'Recently submitted claims awaiting verification.', presetStatus: 'SUBMITTED', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'submit',
    canActivate: [permissionGuard(['claims:create'])],
    loadComponent: () => import('./submit/submit-claim.component').then(m => m.SubmitClaimComponent),
    data: { title: 'Submit Claim', sidebar: 'operational' },
  },
  {
    path: 'credit',
    canActivate: [permissionGuard(['claims:create'])],
    loadComponent: () => import('./submit/submit-claim.component').then(m => m.SubmitClaimComponent),
    data: { title: 'Credit Claim', description: 'Process refund / credit claims.', presetClaimType: 'credit', sidebar: 'operational' },
  },
  {
    path: 'search',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./lookups/member-lookup.component').then(m => m.MemberLookupComponent),
    data: { title: 'Search Member Claims', sidebar: 'operational' },
  },
  {
    path: 'tax-withheld',
    canActivate: [permissionGuard(['claims:view', 'finance:view_withheld_tax'])],
    loadComponent: () => import('./tax-withheld/tax-withheld-list.component').then(m => m.TaxWithheldListComponent),
    data: { title: 'Tax-Withheld Claims', variant: 'medical', sidebar: 'operational', fullbleed: true },
  },

  // ── Pre-authorisation ──────────────────────────────────────────────────────
  // NOTE: The catch-all ":id" claim-detail route lives at the bottom of
  // this file so literal segments (submit, credit, search, preauth, …)
  // resolve first. Placing it above any of those made the router treat
  // "submit" as a UUID and fire GET /api/v1/claims/submit — see the
  // 2026-07-11 bug when Submit Claim was wired.
  {
    path: 'preauth',
    canActivate: [permissionGuard(['claims:manage_preauth']), tenantPreauthGuard],
    loadComponent: () => import('./preauth/pre-auth-list.component').then(m => m.PreAuthListComponent),
    data: { title: 'Pre-Authorizations', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'preauth/new',
    canActivate: [permissionGuard(['claims:manage_preauth']), tenantPreauthGuard],
    loadComponent: () => import('./preauth/pre-auth-form.component').then(m => m.PreAuthFormComponent),
    data: { title: 'New Pre-Auth', sidebar: 'operational' },
  },
  {
    path: 'preauth/:id',
    canActivate: [permissionGuard(['claims:manage_preauth']), tenantPreauthGuard],
    loadComponent: () => import('./preauth/pre-auth-detail.component').then(m => m.PreAuthDetailComponent),
    data: { title: 'Pre-Auth Detail', sidebar: 'operational' },
  },
  // ── Verification ───────────────────────────────────────────────────────────
  // Removed on 2026-07-11 — operator submissions land VERIFIED at capture
  // time, so the front-desk read-back-a-code page no longer has a job.
  // When the provider portal ships and providers self-capture, the
  // verification flow (and this page) will come back for those claims.

  // ── Configuration (tariffs, modifiers, rejection reasons, ICD) ────────────
  {
    path: 'tariffs',
    canActivate: [permissionGuard(['claims:manage_tariffs'])],
    loadComponent: () => import('./tariffs/tariff-schedules-list.component').then(m => m.TariffSchedulesListComponent),
    data: { title: 'Tariff Schedules', sidebar: 'operational', fullbleed: true },
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
    data: { title: 'Tariff Codes', sidebar: 'operational', fullbleed: true },
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
  {
    path: 'scheme-limits',
    canActivate: [permissionGuard(['claims:view'])],
    loadComponent: () => import('./scheme-limits/scheme-limits.component').then(m => m.SchemeLimitsComponent),
    data: { title: 'Scheme Limits', description: 'Read-only per-scheme benefit caps.', sidebar: 'operational' },
  },

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
  // surfaced here so claims clerks don't have to switch sections. Angular
  // rejects a route that mixes `redirectTo` with `canActivate` (NG04014 —
  // redirects fire BEFORE guards, so the guard can never run). The billing
  // side of the redirect already enforces `billing:view_creditors`, so
  // this side just redirects unconditionally.
  {
    path: 'group-charge',
    redirectTo: '/tenant/billing/group-charge',
    pathMatch: 'full' as const,
  },
  rm('special-waivers',
    'Special waivers',
    'Grant a member a one-off override on scheme limits, waiting periods, or age gates.',
    ['members:manage_waivers'],
    {
      blockedBy: 'Needs a WaiverController on user-service (or a member.hasWaiver toggle endpoint) that the rules-engine can read from the MemberFact.',
      willDo: [
        'List active waivers by member with expiry dates.',
        'Grant a waiver with a reason, effective date, and expiry.',
        'Revoke a waiver early if operator granted it in error.',
        'Emit an audit event on every grant / revoke so compliance can review.',
      ],
      currentAlternative: {
        text: 'Right now the rules-engine reads a hasWaiver boolean on MemberFact — surface exceptions via a tenant-scoped rule rather than a per-member override.',
        links: [
          { label: 'Rules', path: '/tenant/admin/rules' },
          { label: 'Members', path: '/tenant/members' },
        ],
      },
    }),

  // ── CTC payments (Cash-To-Cardholder) ─────────────────────────────────────
  {
    path: 'ctc/pending',
    canActivate: [permissionGuard(['claims:view_ctc_payments'])],
    loadComponent: () => import('./ctc/ctc-list.component').then(m => m.CtcListComponent),
    data: { title: 'Pending CTC Payments', description: 'Cash-to-cardholder allocations awaiting commit.', committed: false, sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'ctc/committed',
    canActivate: [permissionGuard(['claims:view_ctc_payments'])],
    loadComponent: () => import('./ctc/ctc-list.component').then(m => m.CtcListComponent),
    data: { title: 'Committed CTC Payments', description: 'Allocated cash-to-cardholder payments.', committed: true, sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'ctc/add',
    canActivate: [permissionGuard(['claims:commit_ctc_payment'])],
    loadComponent: () => import('./ctc/ctc-add.component').then(m => m.CtcAddComponent),
    data: { title: 'Add CTC Payment', sidebar: 'operational' },
  },
  {
    path: 'ctc/auto',
    canActivate: [permissionGuard(['claims:view_ctc_payments'])],
    loadComponent: () => import('./ctc/ctc-auto.component').then(m => m.CtcAutoComponent),
    data: { title: 'Auto CTC Payments', sidebar: 'operational' },
  },

  // ── Drug inventory ─────────────────────────────────────────────────────────
  {
    path: 'drugs',
    canActivate: [permissionGuard(['claims:view_drug'])],
    loadComponent: () => import('./drugs/drugs-list.component').then(m => m.DrugsListComponent),
    data: { title: 'Drug Catalogue', sidebar: 'operational', fullbleed: true },
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
  rm('registration-requests',
    'Provider registration requests',
    'New provider applications awaiting review.',
    ['providers:create'],
    {
      blockedBy: 'Needs a RegistrationRequestController on user-service (or a provider.status=PENDING_APPROVAL filter endpoint).',
      willDo: [
        'List provider applications with contact info, registration #, and supporting documents.',
        'Approve → creates the Provider row and notifies the applicant.',
        'Reject → captures a reason and notifies the applicant.',
      ],
      currentAlternative: {
        text: 'Right now, operators onboard providers directly under Providers → Create.',
        links: [{ label: 'Providers', path: '/tenant/providers' }],
      },
    }),

  // ── Claim detail (catch-all — MUST stay last) ─────────────────────────────
  // A `:id` route matches every literal segment above it, so keeping this
  // at the bottom is load-bearing. Moving it up made /tenant/claims/submit
  // resolve to the detail component and fire GET /api/v1/claims/submit.
  {
    path: ':id',
    // ANY-of: drug-only adjudicators (claims:view_drug without claims:view)
    // must still open the detail page for the drug rows the unified list
    // shows them.
    canActivate: [permissionGuard(['claims:view', 'claims:view_drug'])],
    loadComponent: () => import('./detail/claim-detail.component').then(m => m.ClaimDetailComponent),
    data: { title: 'Claim Detail', sidebar: 'operational' },
  },
];
