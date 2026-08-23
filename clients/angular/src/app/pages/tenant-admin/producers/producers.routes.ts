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
    data: { title: 'Producers' },
  },
  {
    path: 'rate-cards',
    loadComponent: () =>
      import('./rate-cards-list.component').then(m => m.RateCardsListComponent),
    data: { title: 'Commission rate cards' },
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
    data: { title: 'Treaty backfill review', permission: 'finance.producer:backfill_review' },
  },
];
