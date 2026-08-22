import { Routes } from '@angular/router';

/**
 * Tenant-admin reinsurance routes — Reinsurers + Treaties CRUD. Mounted
 * at {@code /tenant/admin/reinsurance/*}.
 *
 * Permissions are guarded server-side via {@code @RequiresPermission} on
 * every controller; the client falls through if the browser hits an
 * endpoint they aren't authorized for.
 */
export const REINSURANCE_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'treaties' },
  {
    path: 'reinsurers',
    loadComponent: () => import('./reinsurers-list.component').then(m => m.ReinsurersListComponent),
    data: { title: 'Reinsurers' },
  },
  {
    path: 'treaties',
    loadComponent: () => import('./treaties-list.component').then(m => m.TreatiesListComponent),
    data: { title: 'Treaties' },
  },
  {
    path: 'treaties/new',
    loadComponent: () => import('./treaty-edit.component').then(m => m.TreatyEditComponent),
    data: { title: 'New Treaty' },
  },
  {
    path: 'treaties/:id',
    loadComponent: () => import('./treaty-edit.component').then(m => m.TreatyEditComponent),
    data: { title: 'Edit Treaty' },
  },
];
