import { Routes } from '@angular/router';
import { permissionGuard } from '../../../../../auth/auth.guard';

/**
 * Phase 15 §21 report routes for the two IFRS 17 keys. Both live under
 * {@code /tenant/finance/reports/ifrs17/*} and gate on the same
 * {@code finance:view_subledger} permission the actuarial reports use,
 * plus a {@link RequiresReport} annotation on the backend controller so
 * a tenant that has disabled either key gets a 403 from the API even
 * if the route resolves.
 *
 * <p>Spread into {@link ../../finance.routes.ts FINANCE_ROUTES} rather
 * than lazy-loaded, matching the actuarial-routes convention.
 */
export const IFRS17_REPORT_ROUTES: Routes = [
  {
    path: 'reports/ifrs17/lrc-lic-reconciliation',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./lrc-lic-reconciliation.component').then(m => m.LrcLicReconciliationComponent),
    data: {
      title: 'IFRS 17 - LRC / LIC reconciliation',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'IFRS17_LRC_LIC_RECONCILIATION',
    },
  },
  {
    path: 'reports/ifrs17/insurance-revenue-service-result',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./insurance-revenue-service-result.component')
        .then(m => m.InsuranceRevenueServiceResultComponent),
    data: {
      title: 'IFRS 17 - insurance revenue & service result',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'IFRS17_INSURANCE_REVENUE_SERVICE_RESULT',
    },
  },
];
