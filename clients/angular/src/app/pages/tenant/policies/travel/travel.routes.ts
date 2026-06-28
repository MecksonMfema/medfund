import { Routes } from '@angular/router';

export const TRAVEL_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./travel-policies-list.component').then(m => m.TravelPoliciesListComponent),
    data: { title: 'Travel Policies', sidebar: 'operational' },
  },
  {
    path: 'add',
    loadComponent: () => import('./travel-policy-form.component').then(m => m.TravelPolicyFormComponent),
    data: { title: 'Add Travel Policy', sidebar: 'operational' },
  },
  {
    path: ':id',
    loadComponent: () => import('./travel-policy-form.component').then(m => m.TravelPolicyFormComponent),
    data: { title: 'Travel Policy Detail', sidebar: 'operational' },
  },
];
