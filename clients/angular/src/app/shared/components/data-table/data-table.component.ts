import { Component, Input, Output, EventEmitter, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { IconComponent } from '../icon/icon.component';

export interface TableColumn {
  key: string;
  label: string;
  type?: string;
  class?: string;
  sortable?: boolean;
}

export interface TableAction {
  label: string;
  icon?: string;
  /** 'default' | 'danger' | 'warning' | 'success' */
  color?: string;
  /** Optional predicate — hides the action when it returns false for a row */
  visible?: (row: any) => boolean;
  handler: (row: any) => void;
}

@Component({
  selector: 'app-data-table',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './data-table.component.html',
  styleUrl: './data-table.component.scss',
})
export class DataTableComponent implements OnInit, OnDestroy {
  @Input() title = '';
  @Input() subtitle = '';
  @Input() columns: TableColumn[] = [];
  @Input() data: any[] = [];
  @Input() actions: TableAction[] = [];
  @Input() showActions = false;
  @Input() emptyMessage = 'No data found';
  @Input() searchable = true;
  @Input() searchPlaceholder = 'Search...';
  @Input() pageSize = 10;
  // Server-side mode
  @Input() serverSide = false;
  @Input() loading = false;
  @Input() totalCount?: number;
  @Input() totalPages?: number;
  @Input() serverPage = 1;
  @Output() rowClick = new EventEmitter<any>();
  @Output() searchChange = new EventEmitter<string>();
  @Output() pageChange = new EventEmitter<number>();

  searchTerm = '';
  sortKey = '';
  sortDirection: 'asc' | 'desc' = 'asc';
  currentPage = 1;

  Math = Math;

  private searchSubject = new Subject<string>();
  private destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.searchSubject.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      takeUntil(this.destroy$),
    ).subscribe(term => this.searchChange.emit(term));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get filteredData(): any[] {
    // In server-side mode the API handles filtering; return data as-is
    if (this.serverSide) return this.data;

    let result = [...this.data];

    if (this.searchTerm) {
      const term = this.searchTerm.toLowerCase();
      result = result.filter((row) =>
        this.columns.some((col) => {
          const val = row[col.key];
          return val != null && val.toString().toLowerCase().includes(term);
        })
      );
    }

    if (this.sortKey) {
      result.sort((a, b) => {
        const va = a[this.sortKey] ?? '';
        const vb = b[this.sortKey] ?? '';
        const cmp = va < vb ? -1 : va > vb ? 1 : 0;
        return this.sortDirection === 'asc' ? cmp : -cmp;
      });
    }

    return result;
  }

  get clientTotalPages(): number {
    return Math.ceil(this.filteredData.length / this.pageSize);
  }

  get paginatedData(): any[] {
    // In server-side mode return all data (pagination handled by load-more)
    if (this.serverSide) return this.data;
    const start = (this.currentPage - 1) * this.pageSize;
    return this.filteredData.slice(start, start + this.pageSize);
  }

  get displayCount(): number {
    return this.serverSide ? (this.totalCount ?? this.data.length) : this.filteredData.length;
  }

  get visiblePages(): number[] {
    const pages: number[] = [];
    const total = this.clientTotalPages;
    const current = this.currentPage;
    const start = Math.max(1, current - 2);
    const end = Math.min(total, start + 4);
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  onSearch(): void {
    if (this.serverSide) {
      this.searchSubject.next(this.searchTerm);
    } else {
      this.currentPage = 1;
    }
  }

  goToServerPage(page: number): void {
    const max = this.totalPages ?? 1;
    if (page >= 1 && page <= max) {
      this.pageChange.emit(page);
    }
  }

  get serverVisiblePages(): number[] {
    const total = this.totalPages ?? 1;
    const current = this.serverPage;
    const start = Math.max(1, current - 2);
    const end = Math.min(total, start + 4);
    const pages: number[] = [];
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  sort(key: string): void {
    if (this.sortKey === key) {
      this.sortDirection = this.sortDirection === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortKey = key;
      this.sortDirection = 'asc';
    }
  }

  goToPage(page: number): void {
    if (page >= 1 && page <= this.clientTotalPages) {
      this.currentPage = page;
    }
  }

  /** Converts snake_case or SCREAMING_SNAKE to Title Case. e.g. super_admin → Super Admin */
  toLabel(value: any): string {
    if (value == null) return '';
    return String(value)
      .toLowerCase()
      .replace(/_/g, ' ')
      .replace(/\b\w/g, c => c.toUpperCase());
  }
}
