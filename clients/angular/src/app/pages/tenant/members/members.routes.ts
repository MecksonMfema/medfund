import { Routes } from '@angular/router';
import { permissionGuard } from '../../../auth/auth.guard';
import { PermissionKey } from '../../../core/security/permissions';

const loadComingSoon = () =>
  import('../../../shared/components/coming-soon/coming-soon.component').then(m => m.ComingSoonComponent);

/**
 * Members domain. The list and the per-member detail (which also handles
 * inline dependants) are real shells; remaining utilities (waivers, history,
 * bulk dependant tools) scaffold to ComingSoon for now.
 *
 * Dependants are administered exclusively inside Member Detail — there is
 * no tenant-wide dependants list page. Groups CRUD lives under billing —
 * see billing.routes.ts and the Members → Groups sidebar entry.
 */
const cs = (
  path: string,
  title: string,
  ref: string,
  description: string,
  perms: PermissionKey[],
): import('@angular/router').Route => ({
  path,
  canActivate: [permissionGuard(perms)],
  loadComponent: loadComingSoon,
  data: { title, description, sidebar: 'operational', ref },
});

export const MEMBERS_ROUTES: Routes = [
  // ── Members list (real shell — existing functional component) ────────────
  {
    path: '',
    canActivate: [permissionGuard(['members:view'])],
    loadComponent: () => import('../../members/members.component').then(m => m.MembersComponent),
    data: { title: 'Members', sidebar: 'operational' },
  },

  // Enrolment form — real component, gated by members:create.
  {
    path: 'add',
    canActivate: [permissionGuard(['members:create'])],
    loadComponent: () => import('./add/member-form.component').then(m => m.MemberFormComponent),
    data: { title: 'Add Member', sidebar: 'operational' },
  },

  // Member detail — view + in-place edit + inline dependants editor.
  {
    path: ':id',
    canActivate: [permissionGuard(['members:view'])],
    loadComponent: () => import('./detail/member-detail.component').then(m => m.MemberDetailComponent),
    data: { title: 'Member Detail', sidebar: 'operational' },
  },

  // Legacy edit URL — redirects to the unified detail page.
  { path: ':id/edit', redirectTo: ':id', pathMatch: 'full' },

  cs(':id/history', 'Member History', '/member-history', 'Claim, payment, contribution history.', ['members:view_history']),
  cs(':id/waivers', 'Member Waivers', '/special-waivers', 'Override benefit limits for this member.', ['members:manage_waivers']),
];
