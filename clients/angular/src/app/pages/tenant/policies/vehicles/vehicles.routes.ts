import { Routes } from '@angular/router';

export const VEHICLES_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./vehicles-list.component').then(m => m.VehiclesListComponent),
    data: { title: 'Vehicles', sidebar: 'operational', fullbleed: true },
  },
  {
    path: 'add',
    loadComponent: () => import('./vehicle-form.component').then(m => m.VehicleFormComponent),
    data: { title: 'Add Vehicle', sidebar: 'operational' },
  },
  {
    path: ':id',
    loadComponent: () => import('./vehicle-form.component').then(m => m.VehicleFormComponent),
    data: { title: 'Vehicle Detail', sidebar: 'operational' },
  },
];
