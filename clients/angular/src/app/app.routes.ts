import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './auth/auth.guard';
import { LayoutComponent } from './layout/layout.component';
import { TenantLayoutComponent } from './layout/tenant-layout/tenant-layout.component';

// Lazy load the role redirect component - reused by multiple routes
const loadRoleRedirect = () =>
  import('./pages/role-redirect/role-redirect.component').then(m => m.RoleRedirectComponent);

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
    ],
  },
  // Tenant-scoped routes — uses dedicated TenantLayoutComponent (dark teal sidebar)
  {
    path: 'tenant',
    component: TenantLayoutComponent,
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      { path: 'dashboard', loadComponent: () => import('./pages/dashboard/dashboard.component').then(m => m.DashboardComponent), data: { title: 'Dashboard' } },
      { path: 'claims', loadComponent: () => import('./pages/claims/claims.component').then(m => m.ClaimsComponent), data: { title: 'Claims' } },
      { path: 'contributions', loadComponent: () => import('./pages/contributions/contributions.component').then(m => m.ContributionsComponent), data: { title: 'Contributions' } },
      { path: 'finance', loadComponent: () => import('./pages/finance/finance.component').then(m => m.FinanceComponent), data: { title: 'Finance' } },
      { path: 'members', loadComponent: () => import('./pages/members/members.component').then(m => m.MembersComponent), data: { title: 'Members' } },
      { path: 'admin', loadComponent: () => import('./pages/admin/admin.component').then(m => m.AdminComponent), data: { title: 'Administration' } },
    ],
  },
  // Legacy route redirects — catch old URLs and role-redirect them
  { path: 'dashboard', canActivate: [authGuard], loadComponent: loadRoleRedirect },
  { path: 'claims', canActivate: [authGuard], loadComponent: loadRoleRedirect },
  { path: 'contributions', canActivate: [authGuard], loadComponent: loadRoleRedirect },
  { path: 'finance', canActivate: [authGuard], loadComponent: loadRoleRedirect },
  { path: 'members', canActivate: [authGuard], loadComponent: loadRoleRedirect },
  { path: 'providers', redirectTo: '/platform/providers', pathMatch: 'full' },
  { path: 'admin', canActivate: [authGuard], loadComponent: loadRoleRedirect },
  // Root — role-based redirect
  {
    path: '',
    pathMatch: 'full',
    canActivate: [authGuard],
    loadComponent: loadRoleRedirect,
  },
  { path: 'unauthorized', loadComponent: () => import('./pages/unauthorized/unauthorized.component').then(m => m.UnauthorizedComponent) },
  // Catch-all — also role-redirect (not redirectTo which can break)
  { path: '**', canActivate: [authGuard], loadComponent: loadRoleRedirect },
];
