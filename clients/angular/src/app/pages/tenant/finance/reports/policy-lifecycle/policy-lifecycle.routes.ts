import { Routes } from '@angular/router';
import { permissionGuard } from '../../../../../auth/auth.guard';

/**
 * Phase 13 §C Phase 10 — POLICY_LIFECYCLE report family (POLICY_MOVEMENT,
 * PERSISTENCY_COHORT, GROUP_CENSUS). Backend at
 * {@code /api/v1/reports/policy-lifecycle/*}; the gateway forwards the
 * whole prefix to user-service. Same shape as
 * {@code UNDERWRITING_REPORT_ROUTES} — spread into
 * {@link ../../finance.routes.ts FINANCE_ROUTES} rather than lazy-mounted
 * so the pages live at {@code /tenant/finance/reports/policy-lifecycle/*}.
 */
export const POLICY_LIFECYCLE_REPORT_ROUTES: Routes = [
  {
    path: 'reports/policy-lifecycle/movement',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./movement/movement.component').then(m => m.PolicyMovementReportComponent),
    data: {
      title: 'Policy movement',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'POLICY_MOVEMENT',
    },
  },
  {
    path: 'reports/policy-lifecycle/persistency-cohort',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./persistency-cohort/persistency-cohort.component')
        .then(m => m.PersistencyCohortReportComponent),
    data: {
      title: 'Persistency cohort',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'PERSISTENCY_COHORT',
    },
  },
  {
    path: 'reports/policy-lifecycle/group-census',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./group-census/group-census.component')
        .then(m => m.GroupCensusReportComponent),
    data: {
      title: 'Group census',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'GROUP_CENSUS',
    },
  },
  {
    path: 'reports/claims/provider-network-utilization',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('../claims/provider-network-utilization.component')
        .then(m => m.ProviderNetworkUtilizationReportComponent),
    data: {
      title: 'Provider network utilization',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'PROVIDER_NETWORK_UTILIZATION',
    },
  },
];
