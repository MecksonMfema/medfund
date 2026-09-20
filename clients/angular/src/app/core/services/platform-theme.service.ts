import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { ApiService } from './api.service';
import { BrandingService } from './branding.service';

/**
 * Mirrors tenancy-service {@code PublicBrandingResponse}. Structurally a
 * subset of {@code PlatformSettings}, so a freshly saved settings row can be
 * handed straight to {@link PlatformThemeService#applyFromSettings}.
 */
export interface PlatformBranding {
  platformName: string | null;
  logoUrl: string | null;
  themeTemplateId: string | null;
  darkMode: boolean | null;
  heroTitle: string | null;
  heroSubtitle: string | null;
}

/**
 * Owns the platform-wide (super-admin configured) appearance layer: the
 * colour template applied to {@code document.body} and the light/dark flag on
 * {@code <html data-theme>}.
 *
 * Bootstrapped from the unauthenticated
 * {@code GET /api/v1/public/platform/branding} endpoint via APP_INITIALIZER,
 * so the theme is in place before the first route renders and before any JWT
 * exists. Tenant branding is applied further down the tree (on the tenant
 * shell element), so it naturally overrides this base layer.
 */
@Injectable({ providedIn: 'root' })
export class PlatformThemeService {

  private readonly branding$$ = new BehaviorSubject<PlatformBranding | null>(null);

  /** Latest platform branding, or null until the bootstrap fetch resolves. */
  readonly branding$: Observable<PlatformBranding | null> = this.branding$$.asObservable();

  constructor(private api: ApiService, private brandingService: BrandingService) {}

  /**
   * Fetches and applies the platform branding. Never errors: a failed fetch
   * (tenancy-service down, gateway restarting) must not block app boot, so
   * the app simply keeps the stylesheet defaults.
   */
  bootstrap(): Observable<void> {
    return this.api.get<PlatformBranding>('/public/platform/branding').pipe(
      tap(branding => this.applyFromSettings(branding)),
      map(() => void 0),
      catchError(err => {
        console.warn('[platform-theme] branding fetch failed; keeping defaults', err);
        return of(void 0);
      }),
    );
  }

  /** Applies a branding payload to the DOM and publishes it to subscribers. */
  applyFromSettings(branding: PlatformBranding): void {
    this.brandingService.applyToRoot({
      templateId: branding.themeTemplateId ?? 'platform',
      logoUrl: branding.logoUrl ?? undefined,
    });
    this.setDarkMode(branding.darkMode === true);
    this.branding$$.next(branding);
  }

  /** Flips the {@code data-theme} attribute the dark overrides key off. */
  setDarkMode(dark: boolean): void {
    document.documentElement.dataset['theme'] = dark ? 'dark' : 'light';
  }

  /** Current snapshot, or null before the bootstrap fetch resolves. */
  get current(): PlatformBranding | null {
    return this.branding$$.value;
  }

  /**
   * Absolute, browser-loadable URL for the platform logo, or null when no
   * logo has been uploaded. The server returns a root-relative path, which
   * would resolve against the Angular origin rather than the gateway.
   */
  logoSrc(logoUrl: string | null | undefined): string | null {
    if (!logoUrl) return null;
    return /^https?:\/\//i.test(logoUrl) ? logoUrl : this.api.gatewayUrl(logoUrl);
  }
}
