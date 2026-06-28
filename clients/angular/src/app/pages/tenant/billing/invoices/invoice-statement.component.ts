import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  ContributionsService,
  InvoiceContributionRow,
} from '../../../../core/services/contributions.service';
import { StatementResponse, StatementLine } from '../../../../core/services/statements.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface SchemeGroup {
  schemeName: string;
  insuranceLine: string;
  rows: InvoiceContributionRow[];
  subtotal: number;
}

/**
 * Snapshot-backed statement for one invoice. Mirrors the MASCA reference
 * shape:
 *
 *   1. Header card — holder, period, status, Download PDF
 *   2. Balance brought forward (opening_balance from the snapshot)
 *   3. Contributions section — divided by scheme, each scheme showing
 *      its members + dependants with names + amounts
 *   4. Transactions section — chronological payments + adjustments
 *      from /invoices/{id}/statement, filtered to non-CONTRIBUTION lines
 *   5. Amount due (closing_balance from the snapshot)
 *
 * Page is full-width — billing tables benefit from horizontal room.
 */
@Component({
  selector: 'app-invoice-statement',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './invoice-statement.component.html',
  styleUrl: './invoice-statement.component.scss',
})
export class InvoiceStatementComponent implements OnInit {
  invoiceId = '';
  loading = false;
  errorMessage: string | null = null;

  invoice: any | null = null;          // raw InvoiceResponse
  statement: StatementResponse | null = null;
  contributions: InvoiceContributionRow[] = [];

  schemeGroups: SchemeGroup[] = [];

  // Financial summary derived from statement lines.
  monthlyContributions = 0;
  totalPayments = 0;
  totalAdjustments = 0;

  constructor(
    private route: ActivatedRoute,
    private contribSvc: ContributionsService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.invoiceId = this.route.snapshot.paramMap.get('id') ?? '';
    if (!this.invoiceId) {
      this.errorMessage = 'Invoice id missing from URL';
      return;
    }
    this.load();
  }

  private load(): void {
    this.loading = true;
    forkJoin({
      invoice:       this.contribSvc.getInvoiceById(this.invoiceId),
      statement:     this.contribSvc.getInvoiceStatement(this.invoiceId),
      contributions: this.contribSvc.getInvoiceContributions(this.invoiceId),
    }).subscribe({
      next: ({ invoice, statement, contributions }) => {
        this.invoice = invoice;
        this.statement = statement;
        this.contributions = contributions ?? [];
        this.groupBySchemes();
        this.deriveFinancialTotals();
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || 'Failed to load invoice statement';
        this.toast.error(this.errorMessage ?? 'Failed');
      },
    });
  }

  private groupBySchemes(): void {
    const buckets = new Map<string, SchemeGroup>();
    for (const c of this.contributions) {
      const key = c.schemeName || '(no scheme)';
      let g = buckets.get(key);
      if (!g) {
        g = { schemeName: key, insuranceLine: c.insuranceLine, rows: [], subtotal: 0 };
        buckets.set(key, g);
      }
      g.rows.push(c);
      const amt = parseFloat(c.amount);
      if (Number.isFinite(amt)) g.subtotal += amt;
    }
    this.schemeGroups = Array.from(buckets.values());
  }

  private deriveFinancialTotals(): void {
    this.monthlyContributions = 0;
    this.totalPayments = 0;
    this.totalAdjustments = 0;
    for (const line of (this.statement?.lines ?? [])) {
      const debit  = line.debit  ? parseFloat(line.debit)  : 0;
      const credit = line.credit ? parseFloat(line.credit) : 0;
      if (line.type === 'CONTRIBUTION') {
        this.monthlyContributions += debit;
      } else if (line.type === 'TRANSACTION') {
        // Credit = payment (reduces balance), debit = adjustment (increases)
        if (credit > 0) this.totalPayments += credit;
        if (debit  > 0) this.totalAdjustments += debit;
      } else if (line.type === 'CONTRIBUTION_PAID') {
        this.totalPayments += credit;
      }
    }
  }

  /** Transaction lines only — contributions are rendered via the per-
   *  scheme breakdown above so we don't double-list them in the ledger. */
  get transactionLines(): StatementLine[] {
    return (this.statement?.lines ?? []).filter(l => l.type !== 'CONTRIBUTION');
  }

  /** Friendly name for the contribution row — member name on a member
   *  row, dependant name (with parent context) on a dependant row. */
  personLabel(row: InvoiceContributionRow): string {
    if (row.personType === 'DEPENDANT') {
      return row.dependantName || '—';
    }
    return row.memberName || '—';
  }

  /** Reference column — member_number on a member row, dependant rolls
   *  up to the principal member's number with a "(dep)" suffix. */
  referenceLabel(row: InvoiceContributionRow): string {
    return row.personType === 'DEPENDANT'
        ? `${row.memberNumber} · dep`
        : row.memberNumber;
  }

  pdfUrl(): string { return this.contribSvc.getInvoicePdfUrl(this.invoiceId); }

  /**
   * Format any money value (string or number) to 2 decimal places.
   * Backend returns BigDecimal as strings with variable scale
   * ("65.0000", "0", "115.00") — the operator expects "65.00".
   * Empty / null inputs render as a dash so transaction-row debit /
   * credit cells (only one is populated per line) don't show "0.00"
   * on the empty side.
   */
  money(v: string | number | null | undefined): string {
    if (v === null || v === undefined || v === '') return '';
    const n = typeof v === 'string' ? parseFloat(v) : v;
    if (!Number.isFinite(n)) return '';
    return n.toLocaleString(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  }

  get holderName(): string {
    return this.statement?.header?.targetName ?? (this.invoice?.groupId ? 'Group' : 'Individual');
  }

  get holderType(): string {
    return this.invoice?.groupId ? 'GROUP' : 'INDIVIDUAL';
  }
}
