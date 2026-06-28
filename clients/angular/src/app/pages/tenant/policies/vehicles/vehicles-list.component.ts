import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap, takeUntil } from 'rxjs';
import { PoliciesService, Vehicle } from '../../../../core/services/policies.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * MOTOR / vehicles list. Mirrors the schemes + members list pattern —
 * debounced search, status chip per row, click-through to the edit
 * form. The backend list endpoint returns a raw array today (see
 * VehicleController) so there's no cursor wrapper to unpack.
 */
@Component({
  selector: 'app-vehicles-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './vehicles-list.component.html',
  styleUrl: './vehicles-list.component.scss',
})
export class VehiclesListComponent implements OnInit, OnDestroy {
  vehicles: Vehicle[] = [];
  loading = false;
  searchQuery = '';

  private search$ = new Subject<string>();
  private destroy$ = new Subject<void>();

  constructor(
    private policies: PoliciesService,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.search$
      .pipe(
        debounceTime(400),
        distinctUntilChanged(),
        switchMap((q) => (q ? this.policies.searchVehicles(q) : this.policies.listVehicles())),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (rows) => { this.vehicles = rows ?? []; this.loading = false; },
        error: (err) => {
          this.loading = false;
          this.toast.error(err?.error?.detail || 'Failed to load vehicles');
        },
      });
    this.load();
  }

  ngOnDestroy(): void { this.destroy$.next(); this.destroy$.complete(); }

  load(): void {
    this.loading = true;
    this.policies.listVehicles().subscribe({
      next: (rows) => { this.vehicles = rows ?? []; this.loading = false; },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load vehicles');
      },
    });
  }

  onSearch(): void {
    this.loading = true;
    this.search$.next(this.searchQuery.trim());
  }

  rowClick(v: Vehicle): void {
    this.router.navigate(['/tenant/policies/vehicles', v.id]);
  }
}
