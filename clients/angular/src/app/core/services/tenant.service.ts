import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject } from 'rxjs';
import { TenantBranding } from './branding.service';

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  status: string;
  branding?: TenantBranding;
  timezone: string;
  insuranceLines: string[];
  providerRegLabel: string;
  /**
   * What this tenant calls a Scheme. Common alternatives: Package, Plan,
   * Policy. Singular + plural stored separately so we don't pluralize "Policy"
   * as "Policys" or "Plan" as "Plans" via algorithm. Defaults to Scheme/Schemes
   * — consumers should fall back to those when the field is missing (older
   * cached tenant snapshots predate the setting).
   */
  schemeLabelSingular?: string;
  schemeLabelPlural?: string;

  /**
   * Whether this tenant treats pharmaceutical claims as a separate domain.
   * Health insurers usually want it on (separate adjudication queues, drug
   * inventory, drug pre-auth); car/property/funeral insurers don't have
   * drug claims at all. Defaults to true for back-compat — existing tenants
   * keep seeing the legacy behaviour until they explicitly opt out.
   */
  drugClaimsEnabled?: boolean;

  /**
   * The tenant's primary currency. Used as the implicit fallback when a form
   * does not specify a currency, and as the reporting currency on dashboards.
   * Populated from {@code tenant_currency_config} where {@code is_default = true}.
   */
  defaultCurrencyCode?: string;
}

const SESSION_KEY = 'medfund_current_tenant';

@Injectable({ providedIn: 'root' })
export class TenantService {
  private currentTenant = new BehaviorSubject<Tenant | null>(this.restore());
  tenant$ = this.currentTenant.asObservable();

  constructor(private http: HttpClient) {}

  setTenant(tenant: Tenant): void {
    this.currentTenant.next(tenant);
    try {
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(tenant));
    } catch { /* storage unavailable — degrade gracefully */ }
  }

  getTenant(): Tenant | null {
    return this.currentTenant.getValue();
  }

  getTenantId(): string {
    return this.currentTenant.getValue()?.id || '';
  }

  clearTenant(): void {
    this.currentTenant.next(null);
    try { sessionStorage.removeItem(SESSION_KEY); } catch {}
  }

  private restore(): Tenant | null {
    try {
      const raw = sessionStorage.getItem(SESSION_KEY);
      return raw ? (JSON.parse(raw) as Tenant) : null;
    } catch {
      return null;
    }
  }
}
