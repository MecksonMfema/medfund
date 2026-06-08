import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { Group, GroupsService } from '../../../../core/services/groups.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

@Component({
  selector: 'app-groups-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, DataTableComponent],
  templateUrl: './groups-list.component.html',
  styleUrl: './groups-list.component.scss',
})
export class GroupsListComponent implements OnInit {
  rows: Group[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;
  query = '';

  columns: TableColumn[] = [
    { key: 'name',               label: 'Name' },
    { key: 'registrationNumber', label: 'Registration #' },
    { key: 'contactPerson',      label: 'Contact person' },
    { key: 'contactEmail',       label: 'Email' },
    { key: 'contactPhone',       label: 'Phone' },
    { key: 'status',             label: 'Status', type: 'status' },
  ];

  actions: TableAction[] = [
    {
      label: 'Suspend',
      color: 'danger',
      visible: (row: Group) => row.status !== 'SUSPENDED',
      handler: (row: Group) => this.suspend(row),
    },
  ];

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

  onSearchChange(term: string): void {
    this.query = term;
    this.loading = true;
    this.errorMessage = null;
    this.query$.next(term);
  }

  open(g: Group): void {
    if (g?.id) this.router.navigate(['/tenant/billing/groups', g.id]);
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
