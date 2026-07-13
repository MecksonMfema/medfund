import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { TenantService } from '../../../../core/services/tenant.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * Insurance lines that carry pre-authorization workflows. Pre-auth is a
 * tariff-driven concept: the pipeline rejects claims when their tariff code
 * requires prior approval and no matching approved record exists. That only
 * applies to the three lines whose claims are itemised into tariff lines —
 * HEALTH, GROUP (group health) and TRAVEL. Asset-centric lines (VEHICLE,
 * PROPERTY) and lump-sum lines (LIFE, FUNERAL, DISABILITY) have no tariff
 * schedule and therefore no pre-auth surface.
 *
 * <p>Mirror this set in {@code LINE_ITEM_LINES} in
 * {@code core/models/insurance-lines.ts} when adding lines.
 */
export const PREAUTH_LINES: ReadonlySet<string> = new Set(['HEALTH', 'GROUP', 'TRAVEL']);

export function tenantHasPreauth(lines: string[] | undefined | null): boolean {
  return (lines ?? []).some(l => PREAUTH_LINES.has(l));
}

/**
 * Allows navigation only when the active tenant sells at least one
 * pre-auth-carrying insurance line. Redirects funeral-only, motor-only,
 * property-only tenants back to the dashboard with an explanatory toast.
 * The sidebar item is hidden for the same tenants, so this guard is a
 * defence-in-depth against a bookmarked URL.
 */
export const tenantPreauthGuard: CanActivateFn = () => {
  const tenantService = inject(TenantService);
  const toast = inject(ToastService);
  const router = inject(Router);

  const lines = tenantService.getTenant()?.insuranceLines ?? [];
  if (tenantHasPreauth(lines)) return true;

  toast.error('Pre-authorizations only apply to lines with a tariff schedule (health, group health, travel). Enable one of those lines in tenant settings to use this screen.');
  return router.createUrlTree(['/tenant/dashboard']);
};
