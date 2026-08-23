import { Routes } from '@angular/router';
import { permissionGuard } from '../../../../../auth/auth.guard';

/**
 * Phase 12 §B + §C underwriting reports. Four keys under
 * {@code ReportFamily.UNDERWRITING} — UPR movement, premium register,
 * new business register (§B, contributions-service) plus endorsement
 * register (§C, user-service). Backend prefix is
 * {@code /api/v1/reports/premium/*}; the gateway proxies the whole
 * prefix — the endorsements sub-route registers ahead of the wildcard
 * so registration-order dispatch beats the catchall.
 *
 * <p>Kept in a small module for module-level cohesion; spread into
 * {@link ../../finance.routes.ts FINANCE_ROUTES} rather than
 * lazy-mounted so the underwriting reports live at
 * {@code /tenant/finance/reports/underwriting/*} without a nested
 * {@code loadChildren} shell.
 */
export const UNDERWRITING_REPORT_ROUTES: Routes = [
  {
    path: 'reports/underwriting/upr-movement',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./upr-movement.component').then(m => m.UprMovementReportComponent),
    data: {
      title: 'UPR movement',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'UPR_MOVEMENT',
    },
  },
  {
    path: 'reports/underwriting/premium-register',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./premium-register.component').then(m => m.PremiumRegisterReportComponent),
    data: {
      title: 'Premium register',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'PREMIUM_REGISTER',
    },
  },
  {
    path: 'reports/underwriting/new-business-register',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./new-business-register.component').then(m => m.NewBusinessRegisterReportComponent),
    data: {
      title: 'New business register',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'NEW_BUSINESS_REGISTER',
    },
  },
  {
    path: 'reports/underwriting/endorsement-register',
    canActivate: [permissionGuard(['finance:view_subledger'])],
    loadComponent: () =>
      import('./endorsement-register.component').then(m => m.EndorsementRegisterReportComponent),
    data: {
      title: 'Endorsement register',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'ENDORSEMENT_REGISTER',
    },
  },
];
