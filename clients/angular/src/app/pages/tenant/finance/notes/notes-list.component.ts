import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import {
  CreateNotePayload,
  FinanceNote,
  FinancePageResponse,
  FinanceService,
} from '../../../../core/services/finance.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../shared/components/data-table/data-table.component';

type Mode = 'debit' | 'credit';

@Component({
  selector: 'app-notes-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, DataTableComponent],
  templateUrl: './notes-list.component.html',
  styleUrl: './notes-list.component.scss',
})
export class NotesListComponent implements OnInit {
  mode: Mode = 'debit';
  rows: FinanceNote[] = [];
  currencies: Currency[] = [];
  loading = false;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  form: CreateNotePayload = this.blankForm();

  // Server-side pagination state.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'createdAt';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'reference',    label: 'Reference',   sortable: true },
    { key: 'amount',       label: 'Amount',      sortable: true, type: 'currency' },
    { key: 'currencyCode', label: 'Currency',    sortable: true },
    { key: 'notes',        label: 'Notes' },
    { key: 'createdAt',    label: 'Created',     sortable: true, type: 'date' },
  ];

  constructor(
    private finance: FinanceService,
    private currencyService: CurrencyService,
    private route: ActivatedRoute,
  ) {}

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({ value: c.code, label: `${c.code} — ${c.name}` }));
  }

  ngOnInit(): void {
    this.mode = (this.route.snapshot.data['mode'] as Mode) || 'debit';
    this.fetchPage();
    this.currencyService.listMaster(true).subscribe({
      next: (rows) => {
        this.currencies = rows;
        if (!this.form.currencyCode && rows.length) this.form.currencyCode = rows[0].code;
      },
      error: () => {},
    });
  }

  pageTitle(): string {
    return this.mode === 'debit' ? 'Debit notes' : 'Credit notes';
  }

  pageDescription(): string {
    return this.mode === 'debit'
      ? 'One-off debits booked outside the adjustments table — bank fees, write-offs, manual debits.'
      : 'Goodwill credits and reversals booked outside the adjustments table.';
  }

  fetchPage(): void {
    this.loading = true;
    const stream = this.mode === 'debit'
      ? this.finance.listDebitNotesPaged({
          q: this.searchTerm || undefined,
          sortKey: this.sortKey,
          sortDirection: this.sortDirection,
          page: this.page - 1,
          size: this.pageSize,
        })
      : this.finance.listCreditNotesPaged({
          q: this.searchTerm || undefined,
          sortKey: this.sortKey,
          sortDirection: this.sortDirection,
          page: this.page - 1,
          size: this.pageSize,
        });
    stream.subscribe({
      next: (resp: FinancePageResponse<FinanceNote>) => {
        this.rows = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load';
        this.rows = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading = false;
      },
    });
  }

  onPageChange(page: number): void { this.page = page; this.fetchPage(); }
  onSearchChange(term: string): void { this.searchTerm = term; this.page = 1; this.fetchPage(); }
  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }

  newNote(): void {
    this.form = this.blankForm();
    if (this.currencies.length) this.form.currencyCode = this.currencies[0].code;
    this.showForm = true;
  }

  cancel(): void { this.showForm = false; }

  submit(): void {
    if (!this.form.amount || !this.form.currencyCode) {
      this.errorMessage = 'Amount and currency are required';
      return;
    }
    this.busy = true;
    const obs = this.mode === 'debit'
      ? this.finance.createDebitNote(this.form)
      : this.finance.createCreditNote(this.form);
    obs.subscribe({
      next: () => {
        this.busy = false;
        this.showForm = false;
        this.successMessage = `${this.mode === 'debit' ? 'Debit' : 'Credit'} note recorded.`;
        this.page = 1;
        this.fetchPage();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to record note';
        this.busy = false;
      },
    });
  }

  private blankForm(): CreateNotePayload {
    return { amount: '', currencyCode: '', reference: '', notes: '' };
  }
}
