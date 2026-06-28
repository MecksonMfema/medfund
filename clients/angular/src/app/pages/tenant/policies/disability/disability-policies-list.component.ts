import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap, takeUntil } from 'rxjs';
import { PoliciesService, DisabilityPolicy } from '../../../../core/services/policies.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * DISABILITY policies list. Mirrors the vehicles list pattern —
 * debounced search, status chip per row, click-through to the edit
 * form. The backend list endpoint returns a raw array today (see
 * DisabilityPolicyController) so there's no cursor wrapper to unpack.
 */
@Component({
  selector: 'app-disability-policies-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './disability-policies-list.component.html',
  styleUrl: './disability-policies-list.component.scss',
})
export class DisabilityPoliciesListComponent implements OnInit, OnDestroy {
  policies: DisabilityPolicy[] = [];
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
        switchMap((q) => (q ? this.policiesSvc.searchDisabilityPolicies(q) : this.policiesSvc.listDisabilityPolicies())),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (rows) => { this.policies = rows ?? []; this.loading = false; },
        error: (err) => {
          this.loading = false;
          this.toast.error(err?.error?.detail || 'Failed to load disability policies');
        },
      });
    this.load();
  }

  ngOnDestroy(): void { this.destroy$.next(); this.destroy$.complete(); }

  load(): void {
    this.loading = true;
    this.policiesSvc.listDisabilityPolicies().subscribe({
      next: (rows) => { this.policies = rows ?? []; this.loading = false; },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load disability policies');
      },
    });
  }

  onSearch(): void {
    this.loading = true;
    this.search$.next(this.searchQuery.trim());
  }

  rowClick(p: DisabilityPolicy): void {
    this.router.navigate(['/tenant/policies/disability', p.id]);
  }
}
