import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { finalize } from 'rxjs/operators';
import { HttpLoadingService } from '../services/http-loading.service';

/**
 * Increments the global pending-request counter for every outbound HTTP
 * request and decrements it when the response (or error) lands. The
 * top-of-page progress bar reads from {@link HttpLoadingService.isLoading}
 * to decide whether to animate.
 *
 * Skips paths that fire continuously in the background — silent token
 * refreshes and the actuator probes — so the bar isn't pulsing forever.
 */
const SILENT_PATHS = [
  '/protocol/openid-connect/token',
  '/silent-check-sso',
  '/actuator/health',
];

export const loadingInterceptor: HttpInterceptorFn = (req, next) => {
  const isSilent = SILENT_PATHS.some(p => req.url.includes(p));
  if (isSilent) {
    return next(req);
  }

  const loading = inject(HttpLoadingService);
  loading.begin();
  return next(req).pipe(finalize(() => loading.end()));
};
