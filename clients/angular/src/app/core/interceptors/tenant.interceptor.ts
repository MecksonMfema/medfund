import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { TenantService } from '../services/tenant.service';

export const tenantInterceptor: HttpInterceptorFn = (req, next) => {
  const tenantService = inject(TenantService);
  const router = inject(Router);
  const tenantId = tenantService.getTenantId();

  // Only attach X-Tenant-ID when the user is inside the tenant portal.
  // Platform admin routes (/platform/...) must NOT send this header — the
  // backend treats absent header as "show platform-scoped data".
  const isTenantRoute = router.url.startsWith('/tenant');

  if (tenantId && isTenantRoute) {
    return next(req.clone({ setHeaders: { 'X-Tenant-ID': tenantId } }));
  }
  return next(req);
};
