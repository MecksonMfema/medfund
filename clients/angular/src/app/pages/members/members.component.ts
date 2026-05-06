import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { DataTableComponent } from '../../shared/components/data-table/data-table.component';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { MembersService, Member } from '../../core/services/members.service';

@Component({
  selector: 'app-members',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, StatCardComponent],
  templateUrl: './members.component.html',
  styleUrl: './members.component.scss',
})
export class MembersComponent implements OnInit, OnDestroy {
  members: Member[] = [];
  loading = false;
  searchQuery = '';
  activeCount = 0; enrolledCount = 0; suspendedCount = 0; terminatedCount = 0;

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

  constructor(private membersService: MembersService) {}

  ngOnInit(): void {
    this.search$.pipe(debounceTime(400), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => { this.cursors = []; this.load(); });
    this.load();
  }

  ngOnDestroy(): void { this.destroy$.next(); this.destroy$.complete(); }

  load(cursor?: string): void {
    this.loading = true;
    this.membersService.getPage({ q: this.searchQuery || undefined, cursor, limit: 20 }).subscribe({
      next: (raw: any) => {
        const content: Member[] = Array.isArray(raw) ? raw : (raw?.content ?? []);
        this.members    = content;
        this.nextCursor = Array.isArray(raw) ? null : (raw?.nextCursor ?? null);
        this.activeCount     = content.filter(m => m.status === 'active').length;
        this.enrolledCount   = content.filter(m => m.status === 'enrolled').length;
        this.suspendedCount  = content.filter(m => m.status === 'suspended').length;
        this.terminatedCount = content.filter(m => m.status === 'terminated').length;
        this.loading = false;
      },
      error: () => { this.loading = false; },
    });
  }

  onSearch(): void { this.search$.next(this.searchQuery); }

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
