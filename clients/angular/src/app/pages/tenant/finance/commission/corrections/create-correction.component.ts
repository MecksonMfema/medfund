import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  AdjustmentType,
  CommissionAdjustmentService,
} from '../../../../../core/services/commission-adjustment.service';
import {
  CommissionReportService,
  CommissionStatementRow,
} from '../../../../../core/services/commission-report.service';
import { Producer, ProducerService } from '../../../../../core/services/producer.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';

/**
 * New commission correction — full-page form, mirrors the shared form
 * grammar used by producer-form. The target commission_transaction is
 * picked via a two-step search: producer autocomplete first, then a
 * commission-statement pick from that producer's recent transactions.
 * The user never sees or pastes a UUID.
 */
@Component({
  selector: 'app-create-correction',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './create-correction.component.html',
  styleUrl: './create-correction.component.scss',
})
export class CreateCorrectionComponent implements OnInit {
  private svc = inject(CommissionAdjustmentService);
  private reportSvc = inject(CommissionReportService);
  private producerSvc = inject(ProducerService);
  private router = inject(Router);
  private toast = inject(ToastService);

  submitting = false;
  errorMessage: string | null = null;

  // ── Step 1: pick a producer (autocomplete) ────────────────────────────
  producer: Producer | null = null;
  producerSearchQuery = '';
  producerMatches: Producer[] = [];
  producerSearching = false;
  private producerSearchTimer: ReturnType<typeof setTimeout> | null = null;

  // ── Step 2: pick a commission transaction from that producer ──────────
  transactions: CommissionStatementRow[] = [];
  transactionsLoading = false;
  transactionsError: string | null = null;
  transactionsQuery = '';
  selectedTransaction: CommissionStatementRow | null = null;

  // Default search window: last 6 months. Users rarely correct older
  // accruals; when they do the two date pickers let them broaden.
  periodStart = '';
  periodEnd = '';

  // ── Correction details ────────────────────────────────────────────────
  adjustmentType: AdjustmentType = 'EX_GRATIA';
  adjustmentAmount: number | null = null;
  justification = '';

  readonly adjustmentTypeOptions: SelectOption[] = [
    { value: 'EX_GRATIA',       label: 'Ex-gratia',       description: 'Additional credit outside the rate card' },
    { value: 'VOID',            label: 'Void',            description: 'Full reversal of the target row' },
    { value: 'MANUAL_CLAWBACK', label: 'Manual clawback', description: 'Operator-triggered clawback' },
    { value: 'MANUAL_REVERSAL', label: 'Manual reversal', description: 'Bespoke correction to prior accrual' },
  ];

  get filteredTransactions(): CommissionStatementRow[] {
    const q = this.transactionsQuery.trim().toLowerCase();
    if (!q) return this.transactions.slice(0, 25);
    return this.transactions.filter(t =>
      t.reference.toLowerCase().includes(q) ||
      t.memberId.toLowerCase().includes(q) ||
      t.contributionId.toLowerCase().includes(q) ||
      t.insuranceLine.toLowerCase().includes(q),
    ).slice(0, 25);
  }

  get isValid(): boolean {
    return !!(this.selectedTransaction
      && this.adjustmentAmount !== null && Number(this.adjustmentAmount) !== 0
      && this.justification.trim().length >= 20);
  }

  ngOnInit(): void {
    const today = new Date();
    const start = new Date(today);
    start.setMonth(start.getMonth() - 6);
    this.periodEnd = today.toISOString().slice(0, 10);
    this.periodStart = start.toISOString().slice(0, 10);
  }

  // ── Producer picker ───────────────────────────────────────────────────

  onProducerSearchChange(): void {
    if (this.producerSearchTimer) clearTimeout(this.producerSearchTimer);
    const q = this.producerSearchQuery.trim();
    if (!q) { this.producerMatches = []; return; }
    this.producerSearching = true;
    this.producerSearchTimer = setTimeout(() => {
      this.producerSvc.searchProducers(q, 10).subscribe({
        next: rows => { this.producerMatches = rows; this.producerSearching = false; },
        error: () => { this.producerMatches = []; this.producerSearching = false; },
      });
    }, 300);
  }

  pickProducer(p: Producer): void {
    this.producer = p;
    this.producerSearchQuery = '';
    this.producerMatches = [];
    this.selectedTransaction = null;
    this.loadTransactions();
  }

  clearProducer(): void {
    this.producer = null;
    this.transactions = [];
    this.selectedTransaction = null;
    this.transactionsQuery = '';
  }

  // ── Commission-transaction picker ─────────────────────────────────────

  loadTransactions(): void {
    if (!this.producer) return;
    this.transactionsLoading = true;
    this.transactionsError = null;
    this.transactions = [];
    this.selectedTransaction = null;
    this.reportSvc.getStatement({
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      producerId:  this.producer.id,
    }).subscribe({
      next: envelope => {
        this.transactions = envelope.data ?? [];
        this.transactionsLoading = false;
      },
      error: err => {
        this.transactionsError = err?.error?.detail || err?.error?.title
          || 'Failed to load commission transactions';
        this.transactions = [];
        this.transactionsLoading = false;
      },
    });
  }

  onPeriodChange(): void {
    if (this.producer) this.loadTransactions();
  }

  pickTransaction(t: CommissionStatementRow): void {
    this.selectedTransaction = t;
  }

  clearTransaction(): void {
    this.selectedTransaction = null;
  }

  // ── Submit ────────────────────────────────────────────────────────────

  submit(): void {
    this.errorMessage = null;
    if (!this.selectedTransaction) {
      this.errorMessage = 'Pick a target commission transaction first.';
      return;
    }
    if (this.adjustmentAmount == null || Number(this.adjustmentAmount) === 0) {
      this.errorMessage = 'Correction amount must be a non-zero value.';
      return;
    }
    if (this.justification.trim().length < 20) {
      this.errorMessage = 'Justification must be at least 20 characters.';
      return;
    }
    this.submitting = true;
    this.svc.create({
      targetCommissionTransactionId: this.selectedTransaction.commissionTransactionId,
      adjustmentType: this.adjustmentType,
      adjustmentAmount: Number(this.adjustmentAmount),
      justification: this.justification.trim(),
    }).subscribe({
      next: adj => {
        this.submitting = false;
        this.toast.success('Draft correction created');
        this.router.navigate(['/tenant/finance/commission/corrections', adj.id]);
      },
      error: err => {
        this.submitting = false;
        const detail = err?.error?.detail || err?.error?.title || 'Failed to create correction';
        this.errorMessage = detail;
        this.toast.error(detail);
      },
    });
  }
}
