import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { Group, GroupsService } from '../../../../core/services/groups.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-groups-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, HumanizePipe],
  templateUrl: './groups-list.component.html',
  styleUrl: './groups-list.component.scss',
})
export class GroupsListComponent implements OnInit {
  rows: Group[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;
  query = '';

  private query$ = new Subject<string>();

  constructor(private groups: GroupsService, private router: Router) {}

  ngOnInit(): void {
    this.fetchAll();
    this.query$
      .pipe(
        debounceTime(350),
        distinctUntilChanged(),
        switchMap((q) => (q.trim() ? this.groups.search(q.trim()) : this.groups.list())),
      )
      .subscribe({
        next: (rows) => { this.rows = rows; this.loading = false; },
        error: (err) => {
          this.errorMessage = err?.error?.detail || 'Search failed';
          this.loading = false;
        },
      });
  }

  onQueryChange(): void {
    this.loading = true;
    this.errorMessage = null;
    this.query$.next(this.query);
  }

  edit(g: Group): void {
    this.router.navigate(['/tenant/billing/groups', g.id, 'edit']);
  }

  suspend(g: Group): void {
    if (!confirm(`Suspend group "${g.name}"?`)) return;
    this.pendingId = g.id;
    this.groups.suspend(g.id).subscribe({
      next: (updated) => {
        this.rows = this.rows.map(r => r.id === updated.id ? updated : r);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Suspend failed';
        this.pendingId = null;
      },
    });
  }

  private fetchAll(): void {
    this.loading = true;
    this.groups.list().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load groups';
        this.loading = false;
      },
    });
  }
}
