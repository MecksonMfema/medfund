import { Routes } from '@angular/router';

/**
 * Tenant-admin underwriting routes (Phase 12 §A). Mounted at
 * {@code /tenant/admin/underwriting/*}. Server-side permission guards
 * live on every mutating controller in user-service via
 * {@code @RequiresPermission("underwriting.portfolio:manage" | "underwriting.cohort:manage")}.
 */
export const UNDERWRITING_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'portfolios' },
  {
    path: 'portfolios',
    loadComponent: () =>
      import('./portfolios-list.component').then(m => m.PortfoliosListComponent),
    // fullbleed matches sibling admin list pages so the flush toolbar
    // sits edge-to-edge with the sidebar.
    data: { title: 'IFRS 17 Portfolios', permission: 'underwriting.portfolio:manage', fullbleed: true },
  },
  {
    path: 'portfolios/new',
    loadComponent: () =>
      import('./portfolio-form.component').then(m => m.PortfolioFormComponent),
    data: { title: 'New portfolio', permission: 'underwriting.portfolio:manage' },
  },
  {
    path: 'portfolios/:id/edit',
    loadComponent: () =>
      import('./portfolio-form.component').then(m => m.PortfolioFormComponent),
    data: { title: 'Edit portfolio', permission: 'underwriting.portfolio:manage' },
  },
  {
    path: 'cohorts',
    loadComponent: () =>
      import('./cohorts-list.component').then(m => m.CohortsListComponent),
    data: { title: 'IFRS 17 Cohorts', permission: 'underwriting.cohort:manage' },
  },
  {
    path: 'funds',
    loadComponent: () =>
      import('./funds-list.component').then(m => m.FundsListComponent),
    data: { title: 'VFA Unit-Linked Funds', permission: 'underwriting.fund:manage' },
  },
  {
    path: 'legacy-retrofit',
    loadComponent: () =>
      import('./legacy-retrofit.component').then(m => m.LegacyRetrofitComponent),
    data: { title: 'Legacy policy retrofit', permission: 'underwriting.portfolio:manage' },
  },
];
