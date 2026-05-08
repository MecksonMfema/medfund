import { Routes } from '@angular/router';
import { permissionGuard } from '../../../auth/auth.guard';
import { PermissionKey } from '../../../core/security/permissions';

const loadComingSoon = () =>
  import('../../../shared/components/coming-soon/coming-soon.component').then(m => m.ComingSoonComponent);

/**
 * Members domain — standalone fourth section per the operational-portal
 * plan. The list view is the existing functional shell; everything else
 * scaffolds to ComingSoon for now.
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

  cs('add',                   'Add Member',           '/additions/add-member',                 'Register a new member.',                                  ['members:create']),
  cs(':id',                   'Member Detail',        '/lookup/view-member',                   'Member profile, dependants, history.',                    ['members:view']),
  cs(':id/edit',              'Edit Member',          '/edit-member',                          'Update profile and enrolment.',                           ['members:update']),
  cs(':id/dependants',        'Member Dependants',    '/lookup/view-dependant',                'Dependants linked to this member.',                       ['members:view_dependants']),
  cs(':id/dependants/add',    'Add Dependant',        '/additions/add-dependant',              'Register a new dependant.',                               ['billing:manage_dependants']),
  cs(':id/history',           'Member History',       '/member-history',                       'Claim, payment, contribution history.',                   ['members:view_history']),
  cs(':id/waivers',           'Member Waivers',       '/special-waivers',                      'Override benefit limits for this member.',                ['members:manage_waivers']),

  cs('dependants',            'Dependants',           '/lookup/view-dependant',                'All dependants across members.',                          ['members:view_dependants']),

  cs('groups',                'Groups',               '/lookup/view-group',                    'Employer groups participating in tenant schemes.',        ['billing:manage_groups']),
  cs('groups/add',            'Add Group',            '/additions/add-group',                  'Register a new employer group.',                          ['billing:manage_groups']),
  cs('groups/:id',            'Group Detail',         '/lookup/view-group-detail',             'Single employer group.',                                  ['billing:manage_groups']),
];
