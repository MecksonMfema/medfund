import { firstValueFrom } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { AdminService } from '../core/services/admin.service';
import { BrandingService } from '../core/services/branding.service';
import { TenantService } from '../core/services/tenant.service';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Hydrates {@link TenantService} from the Keycloak JWT's {@code tenant_id}
 * claim before any route activates. Without this, a cold login (empty
 * sessionStorage) races the {@code permissionGuard} 2-second timeout —
 * permissions only fetch after a tenant is set, but the tenant was
 * previously only seeded inside {@code TenantLayoutComponent.ngOnInit},
 * which runs *after* guards. Result: an intermittent 403 on first login
 * that survived hard reload.
 *
 * Called from the keycloak APP_INITIALIZER once authentication settles.
 * Returns a promise that always resolves — bootstrap failures are logged
 * and the app continues, falling back to the existing layout-component
 * bootstrap path.
 */
export async function bootstrapTenantFromJwt(
  keycloak: KeycloakService,
  tenantService: TenantService,
  adminService: AdminService,
  brandingService: BrandingService,
): Promise<void> {
  // Don't clobber a pre-existing session (e.g. F5 with sessionStorage intact).
  if (tenantService.getTenant()) return;

  const token = keycloak.getKeycloakInstance()?.tokenParsed as Record<string, unknown> | undefined;
  const tenantId = typeof token?.['tenant_id'] === 'string' ? (token['tenant_id'] as string) : undefined;

  // Super admin tokens carry tenant_id="platform"; the operational portal
  // can't render meaningfully against that, so we leave the tenant unset
  // and let TenantLayoutComponent redirect them to /platform/tenants.
  if (!tenantId || !UUID_RE.test(tenantId)) return;

  try {
    const full = await firstValueFrom(adminService.getTenantById(tenantId));
    const fullAny = full as unknown as Record<string, unknown>;
    const insuranceLines = await import('../core/models/insurance-lines');
    const term = insuranceLines.parseSchemeTerminology(full.settings);
    tenantService.setTenant({
      id: full.id,
      name: full.name,
      slug: full.slug,
      status: full.status,
      branding: full.branding
        ? brandingService.parseBranding(full.branding as unknown as string)
        : { templateId: 'platform' },
      timezone: full.timezone ?? '',
      insuranceLines: insuranceLines.parseInsuranceLines(full.settings),
      providerRegLabel: insuranceLines.parseProviderRegLabel(full.settings),
      schemeLabelSingular: term.singular,
      schemeLabelPlural:   term.plural,
      drugClaimsEnabled:   insuranceLines.parseDrugClaimsEnabled(full.settings),
      membershipModel:     fullAny['membershipModel'] as 'INDIVIDUAL_ONLY' | 'GROUP_ONLY' | 'BOTH' | undefined,
    });
  } catch (err) {
    // Don't block app boot — TenantLayoutComponent.ngOnInit will retry.
    console.warn('[tenant-bootstrap] failed to seed tenant from JWT', err);
  }
}
