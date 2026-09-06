import { Routes } from '@angular/router';
import { permissionGuard } from '../../../../../../auth/auth.guard';

/**
 * Phase 23 REG8 staff UI routes for the AML/STR alert workflow. Lives
 * under {@code /tenant/finance/reports/compliance/aml-str/*}. Same shape
 * as the other reports.routes files — spread into
 * {@code finance.routes.ts} rather than lazy-loaded so the sidebar link
 * activates the outer chrome.
 *
 * <p>The list page hosts the queue + create/review/file/close modals; a
 * separate detail route lets the reviewer deep-link straight to an alert.
 */
export const AML_ALERT_ROUTES: Routes = [
  {
    path: 'reports/compliance/aml-str/alerts',
    canActivate: [permissionGuard(['compliance:aml_review'])],
    loadComponent: () =>
      import('./aml-alerts-list.component').then(m => m.AmlAlertsListComponent),
    data: {
      title: 'AML/STR alerts',
      description:
        'Raise, review, file, or close Suspicious Transaction Alerts. FILED alerts are terminal. ' +
        'per-STR XLSX filing lands in Phase 26.',
      sidebar: 'operational',
      fullbleed: true,
    },
  },
];
