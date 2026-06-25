import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import {
  CreateNotePayload,
  FinanceNote,
  FinanceService,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

type Mode = 'debit' | 'credit';

@Component({
  selector: 'app-notes-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, SkeletonComponent, CurrencyFormatPipe],
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
    this.refresh();
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

  refresh(): void {
    this.loading = true;
    const stream = this.mode === 'debit'
      ? this.finance.listDebitNotes()
      : this.finance.listCreditNotes();
    stream.subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load';
        this.loading = false;
      },
    });
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
        this.refresh();
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
