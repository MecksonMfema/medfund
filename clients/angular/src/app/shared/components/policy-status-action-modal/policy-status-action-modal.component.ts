import { CommonModule } from '@angular/common';
import {
  Component,
  EventEmitter,
  HostListener,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SelectComponent, SelectOption } from '../select/select.component';
import {
  PolicyAction,
  PolicyLifecycleActionRegistryService,
  PolicySource,
} from '../../../core/services/policy-lifecycle-action-registry.service';

export interface PolicyStatusActionSubmit {
  reasonCode: string;
  reasonNote?: string;
}

/**
 * Phase 13 §A per L4 + L5 (grill note 7). Confirmation modal used by all
 * six per-line policy detail pages — reason picker (vocab per line +
 * per action), optional note textarea, cancel/confirm. Reuses the
 * endorsement-modal template shape verbatim so it fits the existing
 * modal styling without new CSS.
 */
@Component({
  selector: 'app-policy-status-action-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent],
  templateUrl: './policy-status-action-modal.component.html',
  styleUrl: './policy-status-action-modal.component.scss',
})
export class PolicyStatusActionModalComponent implements OnChanges {
  @Input() open = false;
  @Input() action: PolicyAction | null = null;
  @Input({ required: true }) policySource!: PolicySource;
  @Input() policyLabel = '';
  @Input() submitting = false;
  @Input() serverError: string | null = null;

  @Output() cancel = new EventEmitter<void>();
  @Output() submit = new EventEmitter<PolicyStatusActionSubmit>();

  registry = inject(PolicyLifecycleActionRegistryService);

  reasonCode = '';
  reasonNote = '';
  clientError: string | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    // Reset the form when the modal re-opens or the action changes so a
    // previous session's inputs don't leak in.
    if (changes['open'] && this.open) {
      this.reasonCode = '';
      this.reasonNote = '';
      this.clientError = null;
    }
    if (changes['action']) {
      this.reasonCode = '';
    }
  }

  get title(): string {
    return this.action ? this.registry.actionLabel(this.action) : '';
  }

  get reasonOptions(): SelectOption[] {
    const opts = this.registry.reasonsFor(this.policySource).map(r => ({ value: r.code, label: r.label }));
    return [{ value: '', label: '- Select a reason -' }, ...opts];
  }

  onSubmit(): void {
    this.clientError = null;
    if (!this.reasonCode) {
      this.clientError = 'A reason is required.';
      return;
    }
    this.submit.emit({
      reasonCode: this.reasonCode,
      reasonNote: this.reasonNote.trim() || undefined,
    });
  }

  onCancel(): void {
    this.cancel.emit();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.open && !this.submitting) this.onCancel();
  }
}
