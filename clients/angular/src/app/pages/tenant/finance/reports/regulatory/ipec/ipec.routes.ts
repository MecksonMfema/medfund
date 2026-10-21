import { Routes } from '@angular/router';
import { permissionGuard } from '../../../../../../auth/auth.guard';

/**
 * Route for the IPEC quarterly return regulator report page. Lives under
 * {@code /tenant/finance/reports/regulatory/ipec/quarterly-return}.
 * Spread into {@link ../../../finance.routes.ts FINANCE_ROUTES} rather
 * than lazy-loaded so the sidebar chrome stays active, matching the
 * PMB / IFRS 17 convention.
 *
 * <p>The backend controller already gates on jurisdiction
 * ({@code ZW_IPEC_SHORT_TERM}), country ({@code ZW}),
 * {@code RequiresReport(IPEC_QUARTERLY_RETURN)}, and one of
 * {@code finance:view} / {@code finance:export_regulatory}. The route
 * guard here is deliberately narrow ({@code finance:view}) so a tenant
 * admin who has enabled the toggle can still land on the page and see
 * the 403 explaining any missing permission.
 */
export const IPEC_QUARTERLY_RETURN_ROUTES: Routes = [
  {
    path: 'reports/regulatory/ipec/quarterly-return',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () =>
      import('./ipec-quarterly-return.component').then(m => m.IpecQuarterlyReturnComponent),
    data: {
      title: 'IPEC quarterly return',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'IPEC_QUARTERLY_RETURN',
    },
  },
];
