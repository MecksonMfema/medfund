import { Routes } from '@angular/router';

export const FUNERAL_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./funeral-policies-list.component').then(m => m.FuneralPoliciesListComponent),
    data: { title: 'Funeral Policies', sidebar: 'operational' },
  },
  {
    path: 'add',
    loadComponent: () => import('./funeral-policy-form.component').then(m => m.FuneralPolicyFormComponent),
    data: { title: 'Add Funeral Policy', sidebar: 'operational' },
  },
  {
    path: ':id',
    loadComponent: () => import('./funeral-policy-form.component').then(m => m.FuneralPolicyFormComponent),
    data: { title: 'Funeral Policy Detail', sidebar: 'operational' },
  },
];
