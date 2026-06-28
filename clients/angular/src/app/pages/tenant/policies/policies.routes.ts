import { Routes } from '@angular/router';

/**
 * /tenant/policies/* parent. Each per-line child lazy-loads its own
 * routes file so adding a seventh line is one entry here + one routes
 * file in a new folder.
 */
export const POLICIES_ROUTES: Routes = [
  {
    path: 'vehicles',
    loadChildren: () => import('./vehicles/vehicles.routes').then(m => m.VEHICLES_ROUTES),
  },
  {
    path: 'properties',
    loadChildren: () => import('./properties/properties.routes').then(m => m.PROPERTIES_ROUTES),
  },
  {
    path: 'life',
    loadChildren: () => import('./life/life.routes').then(m => m.LIFE_ROUTES),
  },
  {
    path: 'funeral',
    loadChildren: () => import('./funeral/funeral.routes').then(m => m.FUNERAL_ROUTES),
  },
  {
    path: 'travel',
    loadChildren: () => import('./travel/travel.routes').then(m => m.TRAVEL_ROUTES),
  },
  {
    path: 'disability',
    loadChildren: () => import('./disability/disability.routes').then(m => m.DISABILITY_ROUTES),
  },
];
