import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap, takeUntil } from 'rxjs';
import { PoliciesService, Property } from '../../../../core/services/policies.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * PROPERTY list. Mirrors the vehicles list pattern — debounced search,
 * status chip per row, click-through to the edit form. The backend list
 * endpoint returns a raw array today (see PropertyController) so there's
 * no cursor wrapper to unpack.
 */
@Component({
  selector: 'app-properties-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './properties-list.component.html',
  styleUrl: './properties-list.component.scss',
})
export class PropertiesListComponent implements OnInit, OnDestroy {
  properties: Property[] = [];
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
        switchMap((q) => (q ? this.policies.searchProperties(q) : this.policies.listProperties())),
        takeUntil(this.destroy$),
      )
      .subscribe({
        next: (rows) => { this.properties = rows ?? []; this.loading = false; },
        error: (err) => {
          this.loading = false;
          this.toast.error(err?.error?.detail || 'Failed to load properties');
        },
      });
    this.load();
  }

  ngOnDestroy(): void { this.destroy$.next(); this.destroy$.complete(); }

  load(): void {
    this.loading = true;
    this.policies.listProperties().subscribe({
      next: (rows) => { this.properties = rows ?? []; this.loading = false; },
      error: (err) => {
        this.loading = false;
        this.toast.error(err?.error?.detail || 'Failed to load properties');
      },
    });
  }

  onSearch(): void {
    this.loading = true;
    this.search$.next(this.searchQuery.trim());
  }

  rowClick(p: Property): void {
    this.router.navigate(['/tenant/policies/properties', p.id]);
  }
}
