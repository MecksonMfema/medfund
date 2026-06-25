import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import {
  FinanceService,
  MascaBankAccount,
  UpsertMascaBankAccountPayload,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';

@Component({
  selector: 'app-masca-banks',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, SkeletonComponent],
  templateUrl: './masca-banks.component.html',
  styleUrl: './masca-banks.component.scss',
})
export class MascaBanksComponent implements OnInit {
  rows: MascaBankAccount[] = [];
  currencies: Currency[] = [];
  loading = false;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  editingId: string | null = null;
  form: UpsertMascaBankAccountPayload = this.blankForm();

  constructor(private finance: FinanceService, private currencyService: CurrencyService) {}

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({ value: c.code, label: `${c.code} — ${c.name}` }));
  }

  ngOnInit(): void {
    this.refresh();
    this.currencyService.listMaster(true).subscribe({
      next: (rows) => { this.currencies = rows; },
      error: () => { this.currencies = []; },
    });
  }

  refresh(): void {
    this.loading = true;
    this.finance.listMascaBankAccounts().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load bank accounts';
        this.loading = false;
      },
    });
  }

  newAccount(): void {
    this.editingId = null;
    this.form = this.blankForm();
    if (this.currencies.length) this.form.currencyCode = this.currencies[0].code;
    this.showForm = true;
  }

  edit(row: MascaBankAccount): void {
    this.editingId = row.id;
    this.form = {
      bankName: row.bankName,
      accountNumber: row.accountNumber,
      branchCode: row.branchCode || '',
      swiftCode: row.swiftCode || '',
      accountName: row.accountName,
      currencyCode: row.currencyCode,
      nominated: row.nominated,
      active: row.active,
    };
    this.showForm = true;
  }

  cancel(): void {
    this.showForm = false;
    this.editingId = null;
  }

  submit(): void {
    if (!this.form.bankName.trim() || !this.form.accountNumber.trim() || !this.form.accountName.trim() || !this.form.currencyCode) {
      this.errorMessage = 'Bank name, account number, account name and currency are required';
      return;
    }
    this.busy = true;
    const obs = this.editingId
      ? this.finance.updateMascaBankAccount(this.editingId, this.form)
      : this.finance.createMascaBankAccount(this.form);
    obs.subscribe({
      next: () => {
        this.busy = false;
        this.showForm = false;
        this.editingId = null;
        this.successMessage = 'Bank account saved.';
        this.refresh();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to save';
        this.busy = false;
      },
    });
  }

  remove(row: MascaBankAccount): void {
    if (!confirm(`Delete ${row.bankName} (${row.accountNumber})?`)) return;
    this.busy = true;
    this.finance.deleteMascaBankAccount(row.id).subscribe({
      next: () => {
        this.busy = false;
        this.successMessage = 'Bank account deleted.';
        this.refresh();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete';
        this.busy = false;
      },
    });
  }

  private blankForm(): UpsertMascaBankAccountPayload {
    return {
      bankName: '',
      accountNumber: '',
      branchCode: '',
      swiftCode: '',
      accountName: '',
      currencyCode: '',
      nominated: false,
      active: true,
    };
  }
}
