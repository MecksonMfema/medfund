import { Routes } from '@angular/router';
import { permissionGuard } from '../../../../../../auth/auth.guard';

/**
 * Routes for the two tax regulator reports. Both live under
 * {@code /tenant/finance/reports/regulatory/tax/*} and follow the same
 * async submit → poll → XLSX shape as PMB spend. Backend gates on
 * country (ZW / ZA), report toggle, and finance:view /
 * finance:export_regulatory permission: the frontend guard is narrow
 * ({@code finance:view}) so a tenant admin who enabled the toggle can
 * still land on the page and see any 403 detail inline.
 */
export const TAX_REPORT_ROUTES: Routes = [
  {
    path: 'reports/regulatory/tax/vat-return',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () =>
      import('./vat-return-report.component').then(m => m.VatReturnReportComponent),
    data: {
      title: 'VAT return',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'VAT_RETURN',
    },
  },
  {
    path: 'reports/regulatory/tax/withheld-return',
    canActivate: [permissionGuard(['finance:view'])],
    loadComponent: () =>
      import('./withheld-return-report.component').then(m => m.WithheldReturnReportComponent),
    data: {
      title: 'Tax-withheld return',
      sidebar: 'operational',
      fullbleed: true,
      reportKey: 'TAX_WITHHELD_RETURN',
    },
  },
];
