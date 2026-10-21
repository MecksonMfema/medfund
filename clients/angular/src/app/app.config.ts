import { APP_INITIALIZER, ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { KeycloakService } from 'keycloak-angular';
import { TINYMCE_SCRIPT_SRC } from '@tinymce/tinymce-angular';
import { routes } from './app.routes';
import { initializeKeycloak } from './auth/keycloak.init';
import { tenantInterceptor } from './core/interceptors/tenant.interceptor';
import { loadingInterceptor } from './core/interceptors/loading.interceptor';
import { AdminService } from './core/services/admin.service';
import { BrandingService } from './core/services/branding.service';
import { FeatureFlagService } from './core/services/feature-flag.service';
import { PlatformThemeService } from './core/services/platform-theme.service';
import { TenantService } from './core/services/tenant.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([loadingInterceptor, tenantInterceptor])),
    provideAnimations(),
    KeycloakService,
    {
      provide: APP_INITIALIZER,
      useFactory: initializeKeycloak,
      multi: true,
      deps: [KeycloakService, TenantService, AdminService, BrandingService, FeatureFlagService],
    },
    // Platform branding hits an unauthenticated endpoint, so it runs
    // independently of the Keycloak dance above and is in place before the
    // first route renders. Feature flags need a JWT, so they are bootstrapped
    // from inside initializeKeycloak once the session is established.
    {
      provide: APP_INITIALIZER,
      multi: true,
      deps: [PlatformThemeService],
      useFactory: (theme: PlatformThemeService) => () => firstValueFrom(theme.bootstrap()),
    },
    // Self-hosted TinyMCE — assets are copied to /tinymce/ via angular.json,
    // so the editor wrapper loads tinymce.min.js from there instead of the
    // cloud CDN. Provided at root so any <editor> in the app picks it up.
    { provide: TINYMCE_SCRIPT_SRC, useValue: '/tinymce/tinymce.min.js' },
  ],
};
