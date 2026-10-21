import { Routes } from '@angular/router';

/**
 * Tenant-admin producer / broker routes. Mounted at
 * {@code /tenant/admin/producers/*}. Permissions are guarded server-side
 * via {@code @RequiresPermission} on every controller in finance-service.
 *
 * Phase 11 §A ships: producer + rate-card CRUD and the producer-side view
 * of open member assignments. Bulk reassign + termination + hierarchy tree
 * land in Phase 9 §B; treaty backfill review in Phase 10 §B.
 */
export const PRODUCERS_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'list' },
  {
    path: 'list',
    loadComponent: () =>
      import('./producers-list.component').then(m => m.ProducersListComponent),
    // fullbleed matches /tenant/admin/reinsurance/treaties so the
    // page-header banner + filter strip sit flush with the sidebar.
    data: { title: 'Producers', fullbleed: true },
  },
  // Static form routes are declared BEFORE the :id/* routes below so
  // 'new' cannot be captured as a producer id.
  {
    path: 'new',
    loadComponent: () =>
      import('./producer-form.component').then(m => m.ProducerFormComponent),
    data: { title: 'New producer' },
  },
  {
    path: ':id/edit',
    loadComponent: () =>
      import('./producer-form.component').then(m => m.ProducerFormComponent),
    data: { title: 'Edit producer' },
  },
  {
    path: 'rate-cards',
    loadComponent: () =>
      import('./rate-cards-list.component').then(m => m.RateCardsListComponent),
    // fullbleed matches the sibling producers/list so the page-header
    // banner + filter strip sit flush with the sidebar.
    data: { title: 'Commission rate cards', fullbleed: true },
  },
  {
    path: 'rate-cards/new',
    loadComponent: () =>
      import('./rate-card-form.component').then(m => m.RateCardFormComponent),
    data: { title: 'New rate card' },
  },
  {
    path: 'rate-cards/:id/edit',
    loadComponent: () =>
      import('./rate-card-form.component').then(m => m.RateCardFormComponent),
    data: { title: 'Edit rate card' },
  },
  {
    path: ':id/assignments',
    loadComponent: () =>
      import('./producer-assignments.component').then(m => m.ProducerAssignmentsComponent),
    data: { title: 'Producer assignments' },
  },
  {
    path: ':id/reassign',
    loadComponent: () =>
      import('./bulk-reassign.component').then(m => m.BulkReassignComponent),
    data: { title: 'Bulk reassign', permission: 'finance.producer:manage' },
  },
  {
    path: 'backfill',
    loadComponent: () =>
      import('./backfill-review.component').then(m => m.BackfillReviewComponent),
    // fullbleed matches sibling list routes so the page-header banner sits
    // flush with the sidebar.
    data: { title: 'Treaty backfill', permission: 'finance.producer:backfill_review', fullbleed: true },
  },
];
