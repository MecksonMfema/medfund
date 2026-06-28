import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap, takeUntil } from 'rxjs';
import { FuneralPolicy, PoliciesService } from '../../../../core/services/policies.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * FUNERAL policies list. Mirrors the vehicles list pattern — debounced
 * search, status chip per row, click-through to the edit form. The
 * backend list endpoint returns a raw array today (see
 * FuneralPolicyController) so there's no cursor wrapper to unpack.
 */
@Component({
  selector: 'app-funeral-policies-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './funeral-policies-list.component.html',
  styleUrl: './funeral-policies-list.component.scss',
})
export class FuneralPoliciesListComponent implements OnInit, OnDestroy {
  policies: FuneralPolicy[] = [];
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
        switchMap((q) => (q ? this.policiesSvc.searchFuneralPolicies(q) : this.policiesSvc.listFuneralPolicies())),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (rows) => { this.policies = rows ?? []; this.loading = false; },
        error: (err) => {
          this.loading = false;
          this.toast.error(err?.error?.detail || 'Failed to load funeral policies');
        },
      });
    this.load();
  }

  ngOnDestroy(): void { this.destroy$.next(); this.destroy$.complete(); }

  load(): void {
    this.loading = true;
    this.policiesSvc.listFuneralPolicies().subscribe({
      next: (rows) => { this.policies = rows ?? []; this.loading = false; },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load funeral policies');
      },
    });
  }

  onSearch(): void {
    this.loading = true;
    this.search$.next(this.searchQuery.trim());
  }

  rowClick(p: FuneralPolicy): void {
    this.router.navigate(['/tenant/policies/funeral', p.id]);
  }
}
