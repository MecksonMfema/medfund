import { Routes } from '@angular/router';
import { permissionGuard } from '../../../../../../auth/auth.guard';

/**
 * Route for the PMB Spend regulator report page. Lives under
 * {@code /tenant/finance/reports/regulatory/pmb-spend}. Spread into
 * {@link ../../../finance.routes.ts FINANCE_ROUTES} rather than lazy-loaded
 * so the sidebar chrome stays active, matching the IFRS 17 + AML/STR
 * convention.
 *
 * <p>The backend controller already gates on jurisdiction
 * ({@code ZA_CMS_MEDICAL_SCHEME}), country ({@code ZA}),
 * {@code RequiresReport(PMB_SPEND)}, and one of
 * {@code finance:view} / {@code finance:export_regulatory}. The route
 * guard here is deliberately narrow ({@code finance:view}) so a tenant
 * admin who has enabled the toggle can still land on the page and see
 * the 403 explaining the missing permission if any.
 */
export const PMB_SPEND_REPORT_ROUTES: Routes = [
  {
    path: 'reports/regulatory/pmb-spend',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () =>
      import('./pmb-spend-report.component').then(m => m.PmbSpendReportComponent),
    data: {
      title: 'PMB spend',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'PMB_SPEND',
    },
  },
];
