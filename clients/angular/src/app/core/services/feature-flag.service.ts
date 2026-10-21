import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { AdminService, PlatformFeatureFlag } from './admin.service';

/**
 * Keys of the hard-coded catalogue in
 * {@code services/java/shared/.../flags/PlatformFlag.java}. Adding a value
 * there means adding it here so consumers get compile-time checking.
 */
export type PlatformFlagKey =
  | 'AI_ADJUDICATION'
  | 'FRAUD_DETECTION'
  | 'GROUP_PORTAL'
  | 'PROVIDER_PORTAL'
  | 'MOBILE_PWA';

/**
 * Client-side view of the platform feature flags. Loaded once per app
 * bootstrap (the endpoint is authenticated, so only after a JWT exists) and
 * then read synchronously by consumers. Server-side toggles reach running
 * clients on the next full page load; live push is deliberately out of scope
 * (see the plan's "What We're NOT Doing").
 */
@Injectable({ providedIn: 'root' })
export class FeatureFlagService {

  private readonly flags$$ = new BehaviorSubject<Record<string, boolean>>({});

  readonly flags$: Observable<Record<string, boolean>> = this.flags$$.asObservable();

  constructor(private admin: AdminService) {}

  /**
   * Fetches the catalogue. Never errors: a flag fetch failure must not block
   * app boot, and an empty map means every gate reads as disabled, which is
   * the safe default for a not-yet-launched feature.
   */
  bootstrap(): Observable<void> {
    return this.admin.getFeatureFlags().pipe(
      tap(flags => this.ingest(flags)),
      map(() => void 0),
      catchError(err => {
        console.warn('[feature-flags] fetch failed; all flags read as disabled', err);
        return of(void 0);
      }),
    );
  }

  /** Replaces the cached map from a fresh server payload. */
  ingest(flags: PlatformFeatureFlag[]): void {
    const next: Record<string, boolean> = {};
    for (const flag of flags) next[flag.key] = flag.enabled === true;
    this.flags$$.next(next);
  }

  /** Synchronous read. Unknown or unfetched keys read as disabled. */
  isEnabled(key: PlatformFlagKey): boolean {
    return this.flags$$.value[key] === true;
  }
}
