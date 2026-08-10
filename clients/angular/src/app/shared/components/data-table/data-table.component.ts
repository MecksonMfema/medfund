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
  /** Dynamic label override per row (e.g. toggle "Activate"/"Deactivate"). */
  labelFor?: (row: any) => string;
  /** Dynamic icon override per row. */
  iconFor?: (row: any) => string;
  /** Dynamic color override per row — same vocabulary as {@link color}. */
  colorFor?: (row: any) => string;
  /**
   * Optional permission gate. When set, the host page is expected to filter
   * the action out of the list before passing it in. Declarative metadata
   * only — the data-table itself does not consult PermissionService.
   */
  requiresPermission?: string;
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
  @Input() emptyMessage = 'Nothing here yet';
  @Input() emptyDescription = '';
  @Input() emptyIcon = 'folder-search';
  @Input() searchable = true;
  @Input() searchPlaceholder = 'Search';
  /**
   * Whether to render the built-in toolbar row (which always reserves
   * 48px + a bottom border even when {@code searchable=false} and no
   * {@code [table-filters]}/{@code [table-actions]} content is projected).
   * Set to {@code false} on pages that render their own filter strip
   * above the table — otherwise the empty built-in strip leaves a
   * visible gap between your strip and the first table row.
   */
  @Input() showToolbar = true;
  @Input() pageSize = 20;
  // Server-side mode
  @Input() serverSide = false;
  @Input() loading = false;
  @Input() totalCount?: number;
  @Input() totalPages?: number;
  @Input() serverPage = 1;
  // Cursor pagination (used instead of page numbers when set)
  @Input() hasPrev = false;
  @Input() hasNext = false;
  @Input() initialSortKey = '';
  @Input() initialSortDirection: 'asc' | 'desc' = 'asc';
  @Output() rowClick = new EventEmitter<any>();
  @Output() searchChange = new EventEmitter<string>();
  @Output() pageChange = new EventEmitter<number>();
  @Output() prevPage = new EventEmitter<void>();
  @Output() nextPage = new EventEmitter<void>();
  /**
   * Fires whenever the user clicks a sortable column header. In server-side
   * mode the host must re-fetch using these values; in client-side mode the
   * component still sorts the loaded array but emits the event so consumers
   * can sync URL state or other listeners.
   */
  @Output() sortChange = new EventEmitter<{ key: string; direction: 'asc' | 'desc' }>();

  searchTerm = '';
  sortKey = '';
  sortDirection: 'asc' | 'desc' = 'asc';
  currentPage = 1;

  Math = Math;

  private searchSubject = new Subject<string>();
  private destroy$ = new Subject<void>();

  ngOnInit(): void {
    if (this.initialSortKey) {
      this.sortKey = this.initialSortKey;
      this.sortDirection = this.initialSortDirection;
    }
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
    this.sortChange.emit({ key: this.sortKey, direction: this.sortDirection });
  }

  goToPage(page: number): void {
    if (page >= 1 && page <= this.clientTotalPages) {
      this.currentPage = page;
    }
  }

  /**
   * Whether the user is currently filtering with a search term — drives the
   * search-aware empty-state copy. Kept as a getter so the template can
   * reference it directly without a separate change-detection trigger.
   */
  get isSearchActive(): boolean {
    return !!this.searchTerm;
  }

  /**
   * The title shown above the empty-state icon. A search-aware override
   * takes precedence over the host's {@code emptyMessage} so "No benefits
   * configured…" never appears when the user just typed a query that
   * happened to match nothing.
   */
  get effectiveEmptyMessage(): string {
    if (this.isSearchActive) return 'No matching results';
    return this.emptyMessage;
  }

  /**
   * The description below the title. When a search is active, always use the
   * search-aware copy regardless of {@code emptyDescription} — the host's
   * description is written for the truly-empty case and would mislead a
   * user who's just looking at a no-results-for-this-query view.
   */
  get effectiveEmptyDescription(): string {
    if (this.isSearchActive) {
      return `No results match "${this.searchTerm}". Try a different search or clear the filter.`;
    }
    if (this.emptyDescription) return this.emptyDescription;
    return 'New entries will appear here once they are added.';
  }

  /** Converts snake_case or SCREAMING_SNAKE to Title Case. e.g. super_admin → Super Admin */
  toLabel(value: any): string {
    if (value == null) return '';
    return String(value)
      .toLowerCase()
      .replace(/_/g, ' ')
      .replace(/\b\w/g, c => c.toUpperCase());
  }

  /**
   * Friendly label for the insurance-line chip column (Part 4.6).
   * Mirrors the wizard's LINE_LABELS map — kept inline to avoid
   * cross-component coupling for a four-word lookup.
   */
  toLineLabel(value: any): string {
    if (value == null) return '';
    const code = String(value).toUpperCase();
    switch (code) {
      case 'HEALTH':     return 'Health';
      case 'VEHICLE':    return 'Motor';
      case 'MOTOR':      return 'Motor';
      case 'PROPERTY':   return 'Property';
      case 'LIFE':       return 'Life';
      case 'FUNERAL':    return 'Funeral';
      case 'TRAVEL':     return 'Travel';
      case 'DISABILITY': return 'Disability';
      case 'GROUP':      return 'Group';
      default:           return this.toLabel(code);
    }
  }
}
