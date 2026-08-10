import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import {
  CreateNotePayload,
  FinanceService,
  NoteDirection,
  NoteType,
} from '../../../../core/services/finance.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

type Target = 'provider' | 'member';

/**
 * Note-creation form. Direction is picked from the dropdown (no
 * route-pinned variants — the split /debit-notes/new and
 * /credit-notes/new pages were consolidated into this single form).
 * MEMO disables the payee picker (memo notes are payee-less by design
 * and land as {@code providerId=null, memberId=null}).
 */
@Component({
  selector: 'app-note-form',
  standalone: true,
  imports: [CommonModule, FormsModule, EntityPickerComponent, IconComponent, SelectComponent],
  templateUrl: './note-form.component.html',
  styleUrl: './note-form.component.scss',
})
export class NoteFormComponent implements OnInit {
  currencies: Currency[] = [];
  busy = false;
  errorMessage: string | null = null;

  direction: NoteDirection = 'DEBIT';
  target: Target = 'provider';
  providerId = '';
  memberId = '';
  noteType: NoteType = 'TAX_WITHHELD';
  amount = '';
  currencyCode = '';
  reason = '';

  constructor(
    private currencyService: CurrencyService,
    private finance: FinanceService,
    private router: Router,
  ) {}

  readonly directionOptions: SelectOption[] = [
    { value: 'DEBIT', label: 'Debit — payee owes us' },
    { value: 'CREDIT', label: 'Credit — we owe payee more' },
  ];

  readonly noteTypeOptions: SelectOption[] = [
    { value: 'TAX_WITHHELD', label: 'Tax withheld' },
    { value: 'WRITE_OFF', label: 'Write-off' },
    { value: 'GOODWILL', label: 'Goodwill' },
    { value: 'ENDORSEMENT_PREMIUM', label: 'Endorsement premium' },
    { value: 'PREMIUM_REFUND', label: 'Premium refund' },
    { value: 'PROVIDER_OVERPAYMENT_RECOVERY', label: 'Provider overpayment recovery' },
    { value: 'MEMO', label: 'Memo (no payee, no ledger effect)' },
  ];

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({ value: c.code, label: `${c.code} — ${c.name}` }));
  }

  get isMemo(): boolean {
    return this.noteType === 'MEMO';
  }

  ngOnInit(): void {
    this.currencyService.listMaster(true).subscribe({
      next: (rows) => {
        this.currencies = rows;
        if (!this.currencyCode && rows.length) this.currencyCode = rows[0].code;
      },
      error: () => { this.currencies = []; },
    });
  }

  submit(): void {
    if (!this.amount || !this.currencyCode) {
      this.errorMessage = 'Amount and currency are required';
      return;
    }
    if (!this.isMemo) {
      if (this.target === 'provider' && !this.providerId.trim()) {
        this.errorMessage = 'Provider is required';
        return;
      }
      if (this.target === 'member' && !this.memberId.trim()) {
        this.errorMessage = 'Member is required';
        return;
      }
    }
    const payload: CreateNotePayload = {
      direction: this.direction,
      noteType: this.noteType,
      amount: this.amount,
      currencyCode: this.currencyCode,
      reason: this.reason.trim() || undefined,
    };
    if (!this.isMemo) {
      if (this.target === 'provider') payload.providerId = this.providerId.trim();
      else payload.memberId = this.memberId.trim();
    }

    this.busy = true;
    this.finance.createNote(payload).subscribe({
      next: (note) => {
        this.busy = false;
        this.router.navigate(['/tenant/finance/notes', note.id]);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to create note';
        this.busy = false;
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/tenant/finance/notes']);
  }
}
