import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  FinancePageResponse,
  FinanceService,
  NoteDirection,
  NoteRow,
  NoteStatus,
  NoteType,
  ReportResponse,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';

/**
 * Unified notes list — single surface for debit, credit, and memo
 * notes. Direction is always a filter chip (no route-pinned variant
 * pages); the reports/tax-withheld route pins {@code presetNoteType} to
 * scope the list without hiding the type chip.
 */
@Component({
  selector: 'app-notes-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './notes-list.component.html',
  styleUrl: './notes-list.component.scss',
})
export class NotesListComponent implements OnInit {
  rows: NoteRow[] = [];
  loading = false;
  errorMessage: string | null = null;

  presetNoteType: NoteType | '' = '';
  pageTitle = 'Notes';
  pageDescription = 'Debit, credit, and memo notes across providers and members.';

  statusFilter: NoteStatus | '' = '';
  directionFilter: NoteDirection | '' = '';
  typeFilter: NoteType | '' = '';

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly statusOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'pending', label: 'Pending' },
    { value: 'approved', label: 'Approved' },
    { value: 'applied', label: 'Applied' },
    { value: 'reversed', label: 'Reversed' },
  ];

  readonly directionOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'DEBIT', label: 'Debit' },
    { value: 'CREDIT', label: 'Credit' },
  ];

  readonly typeOptions: SelectOption[] = [
    { value: '', label: 'All' },
    { value: 'TAX_WITHHELD', label: 'Tax withheld' },
    { value: 'WRITE_OFF', label: 'Write-off' },
    { value: 'GOODWILL', label: 'Goodwill' },
    { value: 'ENDORSEMENT_PREMIUM', label: 'Endorsement premium' },
    { value: 'PREMIUM_REFUND', label: 'Premium refund' },
    { value: 'PROVIDER_OVERPAYMENT_RECOVERY', label: 'Provider overpayment recovery' },
    { value: 'MEMO', label: 'Memo (no payee)' },
  ];

  readonly columns: TableColumn[] = [
    { key: 'noteNumber',   label: 'Note #',    sortable: true },
    { key: 'memberName',   label: 'Member',    sortable: true },
    { key: 'providerName', label: 'Provider',  sortable: true },
    { key: 'direction',    label: 'Direction', sortable: true, type: 'label' },
    { key: 'noteType',     label: 'Type',      sortable: true, type: 'label' },
    { key: 'amount',       label: 'Amount',    sortable: true, type: 'currency' },
    { key: 'status',       label: 'Status',    sortable: true, type: 'status' },
    { key: 'reason',       label: 'Reason' },
    { key: 'postedAt',     label: 'Posted',    sortable: true, type: 'date' },
    { key: 'createdAt',    label: 'Created',   sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: NoteRow) => this.router.navigate(['/tenant/finance/notes', row.id]),
    },
  ];

  constructor(
    private finance: FinanceService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const data = this.route.snapshot.data;
    if (data['presetNoteType']) {
      this.presetNoteType = data['presetNoteType'];
      this.typeFilter = this.presetNoteType;
    }
    if (data['title']) this.pageTitle = data['title'];
    if (data['description']) this.pageDescription = data['description'];

    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.finance.listNotesPaged({
      status: this.statusFilter || undefined,
      direction: this.directionFilter || undefined,
      noteType: this.typeFilter || undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    }).subscribe({
      next: (envelope: ReportResponse<FinancePageResponse<NoteRow>>) => {
        const page = envelope.data;
        this.rows = page.content;
        this.totalCount = page.total;
        this.totalPages = page.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load notes';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onStatusChange():    void { this.page = 1; this.fetchPage(); }
  onDirectionChange(): void { this.page = 1; this.fetchPage(); }
  onTypeChange():      void { this.page = 1; this.fetchPage(); }

  onPageChange(page: number): void { this.page = page; this.fetchPage(); }
  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }

  /**
   * Toolbar search input — debounced so we don't fire a request per
   * keystroke. Mirrors the pattern in TransactionsListComponent.
   */
  private searchDebounce: ReturnType<typeof setTimeout> | null = null;
  onSearchInput(term: string): void {
    this.searchTerm = term;
    if (this.searchDebounce) clearTimeout(this.searchDebounce);
    this.searchDebounce = setTimeout(() => {
      this.page = 1;
      this.fetchPage();
    }, 300);
  }

  clearFilters(): void {
    this.statusFilter = '';
    this.directionFilter = '';
    if (!this.presetNoteType) this.typeFilter = '';
    this.searchTerm = '';
    this.page = 1;
    this.fetchPage();
  }
}
