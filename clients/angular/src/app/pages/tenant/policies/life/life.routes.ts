import { Routes } from '@angular/router';

export const LIFE_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./life-policies-list.component').then(m => m.LifePoliciesListComponent),
    data: { title: 'Life Policies', sidebar: 'operational' },
  },
  {
    path: 'add',
    loadComponent: () => import('./life-policy-form.component').then(m => m.LifePolicyFormComponent),
    data: { title: 'Add Life Policy', sidebar: 'operational' },
  },
  {
    path: ':id',
    loadComponent: () => import('./life-policy-form.component').then(m => m.LifePolicyFormComponent),
    data: { title: 'Life Policy Detail', sidebar: 'operational' },
  },
];
