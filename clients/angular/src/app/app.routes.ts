import { Routes } from '@angular/router';
import { authGuard, roleGuard, rootRedirectGuard } from './auth/auth.guard';
import { LayoutComponent } from './layout/layout.component';
import { TenantLayoutComponent } from './layout/tenant-layout/tenant-layout.component';

export const routes: Routes = [
  // Platform admin routes
  {
    path: 'platform',
    component: LayoutComponent,
    canActivate: [authGuard, roleGuard(['super_admin'])],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      {
        path: 'dashboard',
        loadComponent: () => import('./pages/platform/dashboard/platform-dashboard.component').then(m => m.PlatformDashboardComponent),
        data: { title: 'Dashboard' },
      },
      {
        path: 'tenants',
        loadComponent: () => import('./pages/platform/tenants/tenants.component').then(m => m.TenantsComponent),
        data: { title: 'Tenant Management' },
      },
      {
        path: 'users',
        loadComponent: () => import('./pages/platform/users/users.component').then(m => m.UsersComponent),
        data: { title: 'User Management' },
      },
      {
        path: 'audit',
        loadComponent: () => import('./pages/platform/audit/audit.component').then(m => m.AuditComponent),
        data: { title: 'Audit Logs' },
      },
      {
        path: 'settings',
        loadComponent: () => import('./pages/platform/settings/settings.component').then(m => m.SettingsComponent),
        data: { title: 'Platform Settings' },
      },
      {
        path: 'analytics',
        loadComponent: () => import('./pages/platform/analytics/analytics.component').then(m => m.AnalyticsComponent),
        data: { title: 'Analytics' },
      },
      {
        path: 'providers',
        loadComponent: () => import('./pages/providers/providers.component').then(m => m.ProvidersComponent),
        data: { title: 'Providers' },
      },
      {
        path: 'jobs',
        loadComponent: () => import('./pages/platform/jobs/jobs-monitor.component').then(m => m.JobsMonitorComponent),
        data: { title: 'Scheduled Jobs' },
      },
    ],
  },
  // Tenant IT-admin routes — uses dedicated TenantLayoutComponent (dark teal sidebar).
  // Mounted under /tenant/admin/* so the URL itself signals "this is the IT
  // admin console", leaving /tenant/* free for any future operational tenant
  // portals (claims, finance, etc.) that we'll reattach later.
  {
    path: 'tenant/admin',
    component: TenantLayoutComponent,
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      { path: 'dashboard', loadComponent: () => import('./pages/dashboard/dashboard.component').then(m => m.DashboardComponent), data: { title: 'Dashboard' } },
      { path: 'users',     loadComponent: () => import('./pages/tenant-users/tenant-users.component').then(m => m.TenantUsersComponent), data: { title: 'Users' } },
      { path: 'audit',     loadComponent: () => import('./pages/tenant-admin/audit/audit.component').then(m => m.TenantAuditComponent), data: { title: 'Audit Logs' } },
      { path: 'rules',     loadComponent: () => import('./pages/tenant-admin/rules/rules.component').then(m => m.TenantRulesComponent), data: { title: 'Rules Engine' } },
      { path: 'settings',  loadComponent: () => import('./pages/tenant-admin/settings/settings.component').then(m => m.TenantSettingsComponent), data: { title: 'Settings' } },
    ],
  },
  // Operational portal — sibling of /tenant/admin/*, same TenantLayoutComponent
  // but the layout reads `data.sidebar` and renders OperationalSidebarComponent
  // instead of the IT-admin sidebar. Each section's children are scaffolded in
  // Phase 4 of the operational-portal plan; today most resolve to ComingSoon.
  {
    path: 'tenant',
    component: TenantLayoutComponent,
    canActivate: [authGuard],
    data: { sidebar: 'operational' },
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      {
        path: 'dashboard',
        loadComponent: () => import('./pages/tenant/dashboard/dashboard.component').then(m => m.TenantOperationalDashboardComponent),
        data: { title: 'Dashboard', sidebar: 'operational' },
      },
      // Each section's full route tree is in its own file — Phase 4 wires
      // ~150 reference routes, mostly to ComingSoon, with the existing
      // functional shells (claims/contributions/finance/members) becoming the
      // index of their respective section.
      {
        path: 'claims',
        loadChildren: () => import('./pages/tenant/claims/claims.routes').then(m => m.CLAIMS_ROUTES),
      },
      {
        path: 'billing',
        loadChildren: () => import('./pages/tenant/billing/billing.routes').then(m => m.BILLING_ROUTES),
      },
      {
        path: 'finance',
        loadChildren: () => import('./pages/tenant/finance/finance.routes').then(m => m.FINANCE_ROUTES),
      },
      {
        path: 'members',
        loadChildren: () => import('./pages/tenant/members/members.routes').then(m => m.MEMBERS_ROUTES),
      },
    ],
  },
  // Legacy unprefixed paths — keep as redirectTo so old bookmarks resolve.
  // Each lands on the equivalent operational route in the new portal; the
  // operational layout's `permissionGuard` then enforces access.
  { path: 'dashboard',     pathMatch: 'full', redirectTo: '/tenant/dashboard' },
  { path: 'claims',        pathMatch: 'full', redirectTo: '/tenant/claims' },
  { path: 'contributions', pathMatch: 'full', redirectTo: '/tenant/billing/schemes' },
  { path: 'finance',       pathMatch: 'full', redirectTo: '/tenant/finance' },
  { path: 'members',       pathMatch: 'full', redirectTo: '/tenant/members' },
  { path: 'providers',     pathMatch: 'full', redirectTo: '/platform/providers' },
  { path: 'admin',         pathMatch: 'full', redirectTo: '/tenant/admin/dashboard' },

  // Root — dispatcher: super_admin → /platform, everyone else → /tenant.
  // The guard returns a UrlTree, so this route never renders a component.
  {
    path: '',
    pathMatch: 'full',
    canActivate: [rootRedirectGuard],
    children: [],
  },
  { path: 'unauthorized', loadComponent: () => import('./pages/unauthorized/unauthorized.component').then(m => m.UnauthorizedComponent) },
  // Catch-all — same dispatcher so a 404'd URL doesn't strand the user.
  { path: '**', canActivate: [rootRedirectGuard], children: [] },
];
