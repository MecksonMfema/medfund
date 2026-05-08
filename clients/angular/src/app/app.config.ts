import { APP_INITIALIZER, ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { KeycloakService } from 'keycloak-angular';
import { TINYMCE_SCRIPT_SRC } from '@tinymce/tinymce-angular';
import { routes } from './app.routes';
import { initializeKeycloak } from './auth/keycloak.init';
import { tenantInterceptor } from './core/interceptors/tenant.interceptor';
import { loadingInterceptor } from './core/interceptors/loading.interceptor';

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
      deps: [KeycloakService],
    },
    // Self-hosted TinyMCE — assets are copied to /tinymce/ via angular.json,
    // so the editor wrapper loads tinymce.min.js from there instead of the
    // cloud CDN. Provided at root so any <editor> in the app picks it up.
    { provide: TINYMCE_SCRIPT_SRC, useValue: '/tinymce/tinymce.min.js' },
  ],
};
