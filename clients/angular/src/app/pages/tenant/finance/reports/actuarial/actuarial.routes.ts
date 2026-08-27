import { Routes } from '@angular/router';
import { permissionGuard } from '../../../../../auth/auth.guard';

/**
 * Phase 14 §Actuarial Phase 10 report routes. The IBNR + LOSS_TRIANGLE
 * pages live at {@code /tenant/finance/reports/actuarial/*}. Both submit
 * async jobs at {@code POST /api/v1/reports/actuarial/{ibnr|loss-triangle}},
 * poll {@code GET /api/v1/reports/actuarial/jobs/{jobId}}, and export via
 * {@code GET /api/v1/reports/actuarial/jobs/{jobId}/export.xlsx}. Later
 * phases (11-14) drop their study pages alongside.
 *
 * <p>Spread into {@link ../../finance.routes.ts FINANCE_ROUTES} rather
 * than lazy-loaded — same shape as
 * {@code UNDERWRITING_REPORT_ROUTES} / {@code POLICY_LIFECYCLE_REPORT_ROUTES}.
 */
export const ACTUARIAL_REPORT_ROUTES: Routes = [
  {
    path: 'reports/actuarial/ibnr-triangle',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./ibnr-triangle.component').then(m => m.IbnrTriangleComponent),
    data: {
      title: 'IBNR triangle',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'IBNR_TRIANGLE',
    },
  },
  {
    path: 'reports/actuarial/loss-triangle',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./loss-triangle.component').then(m => m.LossTriangleComponent),
    data: {
      title: 'Loss triangle',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'LOSS_TRIANGLE',
    },
  },
  {
    path: 'reports/actuarial/persistency-study',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./persistency-study.component').then(m => m.PersistencyStudyComponent),
    data: {
      title: 'Persistency study',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'PERSISTENCY_STUDY',
    },
  },
];
