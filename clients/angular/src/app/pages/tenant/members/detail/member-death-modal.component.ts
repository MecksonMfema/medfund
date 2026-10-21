import { CommonModule } from '@angular/common';
import { Component, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ICD10_CHAPTERS } from '../../../../shared/constants/icd10-chapters';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

/**
 * Payload emitted by {@link MemberDeathModalComponent} on submit. The
 * parent owns the HTTP call so the modal stays presentation-only. The
 * chosen ICD-10 chapter is stored as {@code causeOfDeath}; a "Free text"
 * selection lets the operator type an arbitrary cause when no chapter fits.
 */
export interface RecordMemberDeathPayload {
  deathDate: string;
  causeOfDeath: string | null;
}

const FREE_TEXT_SENTINEL = '__free_text__';

@Component({
  selector: 'app-member-death-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent],
  templateUrl: './member-death-modal.component.html',
  styleUrl: './member-death-modal.component.scss',
})
export class MemberDeathModalComponent implements OnChanges {
  /** Toggle visibility. */
  @Input() open = false;
  /** Displayed on the header for context. */
  @Input() memberName = '';
  /** Member's existing termination_date (YYYY-MM-DD). Bounds the max date
   *  input server-side; the modal enforces it client-side so the operator
   *  gets an inline error rather than a 400 round-trip. */
  @Input() terminationDate: string | null = null;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<RecordMemberDeathPayload>();

  today = new Date().toISOString().slice(0, 10);
  deathDate = this.today;
  causeSelection: string = '';
  causeFreeText = '';
  error: string | null = null;

  readonly causeOptions: SelectOption[] = [
    { value: '', label: 'Not specified' },
    ...ICD10_CHAPTERS.map(c => ({ value: c.code, label: `${c.code} - ${c.label}` })),
    { value: FREE_TEXT_SENTINEL, label: 'Free text (other)' },
  ];

  get maxDeathDate(): string {
    if (this.terminationDate && this.terminationDate < this.today) return this.terminationDate;
    return this.today;
  }

  get showFreeText(): boolean {
    return this.causeSelection === FREE_TEXT_SENTINEL;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open) {
      this.reset();
    }
  }

  onSubmit(): void {
    this.error = null;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(this.deathDate)) {
      this.error = 'Death date must be YYYY-MM-DD.';
      return;
    }
    if (this.deathDate > this.today) {
      this.error = 'Death date cannot be in the future.';
      return;
    }
    if (this.terminationDate && this.deathDate > this.terminationDate) {
      this.error = `Death date must be on or before the termination date (${this.terminationDate}).`;
      return;
    }
    let cause: string | null;
    if (this.causeSelection === FREE_TEXT_SENTINEL) {
      cause = this.causeFreeText.trim() ? this.causeFreeText.trim() : null;
    } else if (this.causeSelection) {
      cause = this.causeSelection;
    } else {
      cause = null;
    }
    this.submit.emit({ deathDate: this.deathDate, causeOfDeath: cause });
  }

  onCancel(): void { this.cancel.emit(); }

  @HostListener('document:keydown.escape')
  onEscape(): void { if (this.open) this.onCancel(); }

  reset(): void {
    this.deathDate = this.maxDeathDate;
    this.causeSelection = '';
    this.causeFreeText = '';
    this.error = null;
  }
}
