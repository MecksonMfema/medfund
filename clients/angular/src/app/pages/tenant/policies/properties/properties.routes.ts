import { Routes } from '@angular/router';

export const PROPERTIES_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./properties-list.component').then(m => m.PropertiesListComponent),
    data: { title: 'Properties', sidebar: 'operational' },
  },
  {
    path: 'add',
    loadComponent: () => import('./property-form.component').then(m => m.PropertyFormComponent),
    data: { title: 'Add Property', sidebar: 'operational' },
  },
  {
    path: ':id',
    loadComponent: () => import('./property-form.component').then(m => m.PropertyFormComponent),
    data: { title: 'Property Detail', sidebar: 'operational' },
  },
];
