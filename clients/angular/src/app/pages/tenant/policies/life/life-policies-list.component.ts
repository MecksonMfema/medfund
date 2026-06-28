import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap, takeUntil } from 'rxjs';
import { LifePolicy, PoliciesService } from '../../../../core/services/policies.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * LIFE policies list. Mirrors the vehicles list shape — debounced
 * search, status chip per row, click-through to the edit form. LIFE
 * policies are person-insuring: insuredMemberId is required, but for
 * the list view we render the raw id as a small hash to avoid a fetch-
 * per-row. Future enhancement: backend can join the member name into
 * the list payload.
 */
@Component({
  selector: 'app-life-policies-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './life-policies-list.component.html',
  styleUrl: './life-policies-list.component.scss',
})
export class LifePoliciesListComponent implements OnInit, OnDestroy {
  policies: LifePolicy[] = [];
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
        switchMap((q) => (q ? this.policiesSvc.searchLifePolicies(q) : this.policiesSvc.listLifePolicies())),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (rows) => { this.policies = rows ?? []; this.loading = false; },
        error: (err) => {
          this.loading = false;
          this.toast.error(err?.error?.detail || 'Failed to load life policies');
        },
      });
    this.load();
  }

  ngOnDestroy(): void { this.destroy$.next(); this.destroy$.complete(); }

  load(): void {
    this.loading = true;
    this.policiesSvc.listLifePolicies().subscribe({
      next: (rows) => { this.policies = rows ?? []; this.loading = false; },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load life policies');
      },
    });
  }

  onSearch(): void {
    this.loading = true;
    this.search$.next(this.searchQuery.trim());
  }

  rowClick(p: LifePolicy): void {
    this.router.navigate(['/tenant/policies/life', p.id]);
  }

  shortId(id: string | null | undefined): string {
    if (!id) return '—';
    return id.length > 8 ? id.slice(0, 8) : id;
  }
}
