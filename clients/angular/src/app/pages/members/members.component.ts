import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { DataTableComponent } from '../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { HasPermissionDirective } from '../../shared/directives/has-permission.directive';
import { MembersService, Member } from '../../core/services/members.service';

/**
 * Members list — mirrors the schemes-page shape (/tenant/billing/schemes):
 * full-bleed page-header banner with a permission-gated primary action,
 * then the shared data-table with its built-in search bar. Cursor-based
 * pagination survives the redesign (the members endpoint is a cursor
 * feed, not an offset one).
 */
@Component({
  selector: 'app-members',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DataTableComponent, IconComponent, HasPermissionDirective],
  templateUrl: './members.component.html',
  styleUrl: './members.component.scss',
})
export class MembersComponent implements OnInit, OnDestroy {
  members: Member[] = [];
  loading = false;
  searchQuery = '';
  errorMessage: string | null = null;

  cursors: string[] = [];
  nextCursor: string | null = null;
  get hasPrev(): boolean { return this.cursors.length > 0; }
  get hasNext(): boolean { return !!this.nextCursor; }

  columns = [
    { key: 'memberNumber', label: 'Member #' },
    { key: 'firstName',    label: 'First Name' },
    { key: 'lastName',     label: 'Last Name' },
    { key: 'email',        label: 'Email' },
    { key: 'status',       label: 'Status',   type: 'status' },
    { key: 'enrollmentDate', label: 'Enrolled', type: 'date' },
  ];

  private search$ = new Subject<string>();
  private destroy$ = new Subject<void>();

  constructor(private membersService: MembersService, private router: Router) {}

  ngOnInit(): void {
    this.search$.pipe(debounceTime(400), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => { this.cursors = []; this.load(); });
    this.load();
  }

  ngOnDestroy(): void { this.destroy$.next(); this.destroy$.complete(); }

  load(cursor?: string): void {
    this.loading = true;
    this.errorMessage = null;
    this.membersService.getPage({ q: this.searchQuery || undefined, cursor, limit: 20 }).subscribe({
      next: (raw: any) => {
        const content: Member[] = Array.isArray(raw) ? raw : (raw?.content ?? []);
        this.members    = content;
        this.nextCursor = Array.isArray(raw) ? null : (raw?.nextCursor ?? null);
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load members';
        this.loading = false;
      },
    });
  }

  onSearch(term: string): void {
    this.searchQuery = term;
    this.search$.next(term);
  }

  onRowClick(row: Member): void {
    if (row?.id) this.router.navigate(['/tenant/members', row.id]);
  }

  nextPage(): void {
    if (!this.nextCursor) return;
    this.cursors.push(this.nextCursor);
    this.load(this.nextCursor);
  }

  prevPage(): void {
    this.cursors.pop();
    this.load(this.cursors.at(-1));
  }
}
