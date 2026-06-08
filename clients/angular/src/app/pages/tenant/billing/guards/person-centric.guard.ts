import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { ContributionsService } from '../../../../core/services/contributions.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { isPersonCentricLine } from '../../../../core/models/insurance-lines';
import { ToastService } from '../../../../shared/components/toast/toast.service';

const REDIRECT = '/tenant/dashboard';

/**
 * Allows navigation only when the active tenant sells at least one
 * person-centric insurance line (HEALTH, LIFE, FUNERAL, GROUP, TRAVEL,
 * DISABILITY). Used to gate the Age Groups screens for tenants that only
 * sell asset-centric lines (VEHICLE, PROPERTY) where age has no actuarial
 * meaning.
 */
export const tenantPersonCentricGuard: CanActivateFn = () => {
  const tenantService = inject(TenantService);
  const toast = inject(ToastService);
  const router = inject(Router);

  const tenant = tenantService.getTenant();
  const lines = tenant?.insuranceLines ?? [];
  if (lines.some(isPersonCentricLine)) return true;

  toast.error('Age groups only apply to person-centric insurance lines. Enable a person-centric line in tenant settings to use this screen.');
  return router.createUrlTree([REDIRECT]);
};

/**
 * Allows navigation only when the scheme referenced by the :schemeId route
 * parameter is on a person-centric line. Used to gate the per-scheme
 * Benefits screens — a VEHICLE/PROPERTY scheme should not expose benefit
 * configuration.
 */
export const schemePersonCentricGuard: CanActivateFn = (route) => {
  const contributions = inject(ContributionsService);
  const toast = inject(ToastService);
  const router = inject(Router);

  const schemeId = route.paramMap.get('schemeId');
  if (!schemeId) {
    // Defensive — route shape changed; fail open so we don't break navigation
    // for a guard misconfiguration.
    return true;
  }

  return contributions.getSchemeById(schemeId).pipe(
    map(scheme => {
      if (isPersonCentricLine(scheme.insuranceLine)) return true;
      toast.error(`Scheme benefits do not apply to ${scheme.insuranceLine ?? 'this'} schemes — they are asset-centric and have no member-level benefit limits.`);
      return router.createUrlTree(['/tenant/billing/schemes']);
    }),
    catchError(() => of(router.createUrlTree(['/tenant/billing/schemes']))),
  );
};
