import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap, takeUntil } from 'rxjs';
import { PoliciesService, TravelPolicy } from '../../../../core/services/policies.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * TRAVEL policies list. Mirrors the vehicles list pattern —
 * debounced search, status chip per row, click-through to the edit
 * form. The backend list endpoint returns a raw array today (see
 * TravelPolicyController) so there's no cursor wrapper to unpack.
 */
@Component({
  selector: 'app-travel-policies-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './travel-policies-list.component.html',
  styleUrl: './travel-policies-list.component.scss',
})
export class TravelPoliciesListComponent implements OnInit, OnDestroy {
  policies: TravelPolicy[] = [];
  loading = false;
  searchQuery = '';

  private search$ = new Subject<string>();
  private destroy$ = new Subject<void>();

  constructor(
    private policiesSvc: PoliciesService,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.search$
      .pipe(
        debounceTime(400),
        distinctUntilChanged(),
        switchMap((q) => (q ? this.policiesSvc.searchTravelPolicies(q) : this.policiesSvc.listTravelPolicies())),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (rows) => { this.policies = rows ?? []; this.loading = false; },
        error: (err) => {
          this.loading = false;
          this.toast.error(err?.error?.detail || 'Failed to load travel policies');
        },
      });
    this.load();
  }

  ngOnDestroy(): void { this.destroy$.next(); this.destroy$.complete(); }

  load(): void {
    this.loading = true;
    this.policiesSvc.listTravelPolicies().subscribe({
      next: (rows) => { this.policies = rows ?? []; this.loading = false; },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load travel policies');
      },
    });
  }

  onSearch(): void {
    this.loading = true;
    this.search$.next(this.searchQuery.trim());
  }

  rowClick(p: TravelPolicy): void {
    this.router.navigate(['/tenant/policies/travel', p.id]);
  }
}
