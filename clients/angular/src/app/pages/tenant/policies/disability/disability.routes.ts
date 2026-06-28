import { Routes } from '@angular/router';

export const DISABILITY_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./disability-policies-list.component').then(m => m.DisabilityPoliciesListComponent),
    data: { title: 'Disability Policies', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'add',
    loadComponent: () => import('./disability-policy-form.component').then(m => m.DisabilityPolicyFormComponent),
    data: { title: 'Add Disability Policy', sidebar: 'operational' },
  },
  {
    path: ':id',
    loadComponent: () => import('./disability-policy-form.component').then(m => m.DisabilityPolicyFormComponent),
    data: { title: 'Disability Policy Detail', sidebar: 'operational' },
  },
];
